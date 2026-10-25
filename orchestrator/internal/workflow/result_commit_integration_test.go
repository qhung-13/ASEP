//go:build integration

package workflow

import (
	"encoding/binary"
	"errors"
	"io"
	"net"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/qhung-13/ASEP/orchestrator/internal/testsupport"
)

// This test-only loopback proxy forwards PostgreSQL startup/authentication and
// framed messages without logging bytes. It affects only the B0-verified target.
// before-forward drops COMMIT before PostgreSQL receives it. after-confirm drops
// PostgreSQL's actual CommandComplete(COMMIT), not an application ACK.
func commitFaultProxy(t *testing.T, mode string) (string, <-chan struct{}) {
	t.Helper()
	upstream, err := url.Parse(testsupport.DatabaseURL(t)) // Performs B0 identity proof first.
	if err != nil {
		t.Fatal("verified proxy target unavailable")
	}
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal("loopback commit proxy unavailable")
	}
	injected := make(chan struct{})
	var once atomic.Bool
	var active sync.Map
	var handlers sync.WaitGroup
	acceptDone := make(chan struct{})
	go func() {
		defer close(acceptDone)
		for {
			client, err := listener.Accept()
			if err != nil {
				return
			}
			active.Store(client, true)
			handlers.Add(1)
			go func() {
				defer handlers.Done()
				defer active.Delete(client)
				defer client.Close()
				server, err := net.DialTimeout("tcp", upstream.Host, time.Second)
				if err != nil {
					return
				}
				active.Store(server, true)
				defer active.Delete(server)
				defer server.Close()
				deadline := time.Now().Add(10 * time.Second)
				_ = client.SetDeadline(deadline)
				_ = server.SetDeadline(deadline)
				// sslmode=disable: startup has a length but no message type byte.
				var length [4]byte
				if _, err := io.ReadFull(client, length[:]); err != nil {
					return
				}
				n := binary.BigEndian.Uint32(length[:])
				if n < 8 || n > 1<<20 {
					return
				}
				startup := make([]byte, int(n))
				copy(startup, length[:])
				if _, err := io.ReadFull(client, startup[4:]); err != nil {
					return
				}
				if err := writeProxyPacket(server, startup); err != nil {
					return
				}
				var droppingResponse atomic.Bool
				serverDone := make(chan struct{})
				go func() {
					defer close(serverDone)
					defer client.Close()
					defer server.Close()
					for {
						packet, err := readProxyPacket(server)
						if err != nil {
							return
						}
						if droppingResponse.Load() && packet[0] == 'C' && string(packet[5:]) == "COMMIT\x00" {
							if mode == "after-confirm-outage" {
								_ = listener.Close()
							}
							close(injected)
							return // Never deliver the genuine successful COMMIT response.
						}
						if err := writeProxyPacket(client, packet); err != nil {
							return
						}
					}
				}()
				defer func() { _ = client.Close(); _ = server.Close(); <-serverDone }()
				for {
					packet, err := readProxyPacket(client)
					if err != nil {
						return
					}
					if packet[0] == 'Q' && strings.EqualFold(strings.TrimSpace(strings.TrimSuffix(string(packet[5:]), "\x00")), "commit") && once.CompareAndSwap(false, true) {
						if mode == "before-forward" {
							close(injected)
							return
						}
						droppingResponse.Store(true)
					}
					if err := writeProxyPacket(server, packet); err != nil {
						return
					}
				}
			}()
		}
	}()
	t.Cleanup(func() {
		_ = listener.Close()
		<-acceptDone
		active.Range(func(key, value any) bool { _ = key.(net.Conn).Close(); return true })
		handlers.Wait()
	})
	proxyURL := *upstream
	proxyURL.Host = listener.Addr().String()
	return proxyURL.String(), injected
}

func readProxyPacket(connection net.Conn) ([]byte, error) {
	var header [5]byte
	if _, err := io.ReadFull(connection, header[:]); err != nil {
		return nil, err
	}
	n := binary.BigEndian.Uint32(header[1:])
	if n < 4 || n > 1<<20 {
		return nil, errors.New("invalid synthetic PostgreSQL frame")
	}
	packet := make([]byte, int(n)+1)
	copy(packet, header[:])
	_, err := io.ReadFull(connection, packet[5:])
	return packet, err
}

func writeProxyPacket(connection net.Conn, packet []byte) error {
	for len(packet) > 0 {
		n, err := connection.Write(packet)
		if err != nil {
			return err
		}
		if n == 0 {
			return io.ErrShortWrite
		}
		packet = packet[n:]
	}
	return nil
}

func TestResultCommitResponseLoss(t *testing.T) {
	for _, mode := range []string{"before-forward", "after-confirm", "after-confirm-outage"} {
		t.Run(mode, func(t *testing.T) {
			s := storeForTest(t)
			admission, run := admitForTest(t, s, "result-commit-wire")
			lease, err := s.ClaimForRun(testContext(t), run.RunID, "synthetic-commit-worker", 30)
			if err != nil {
				t.Fatal("commit fault fixture claim failed")
			}
			c := validAdvance(t, admission, lease)
			before := snapshotFor(t, s, run)
			proxyURL, injected := commitFaultProxy(t, mode)
			faulty, err := Open(testContext(t), proxyURL)
			if err != nil {
				t.Fatal("guarded proxy connection unavailable")
			}
			t.Cleanup(func() { _ = faulty.Close() })
			start := time.Now()
			receipt, err := faulty.advanceInvestigation(testContext(t), c)
			select {
			case <-injected:
			case <-testContext(t).Done():
				t.Fatal("actual COMMIT fault checkpoint was not reached")
			}
			if time.Since(start) > 4*time.Second {
				t.Fatal("commit reconciliation exceeded its bound")
			}
			if mode == "after-confirm" {
				if err != nil {
					t.Fatal("durable acceptance was not reconciled after genuine COMMIT response loss")
				}
				checkAdvanced(t, s, run, receipt)
				t.Log("RF-10: real PostgreSQL CommandComplete(COMMIT) dropped; fresh connection recovered durable receipt; one atomic bundle")
			} else {
				if !errors.Is(err, ErrResultUnknown) || receipt != (advanceReceipt{}) {
					t.Fatal("unproven commit outcome was not UNKNOWN")
				}
				if mode == "before-forward" {
					// Wire-side knowledge is test evidence, not knowledge available
					// to Store: lack of a receipt alone must still remain UNKNOWN.
					if snapshotFor(t, s, run) != before {
						t.Fatal("COMMIT not forwarded but durable state changed")
					}
					if _, err := s.reconcileInvestigation(testContext(t), c); !errors.Is(err, ErrResultUnknown) {
						t.Fatal("absent receipt incorrectly resolved UNKNOWN")
					}
					t.Log("RF-10: connection cut before COMMIT forward; UNKNOWN; no receipt/transition/successor, original snapshot unchanged, no blind retry")
				} else {
					// The proxy outage makes the immediate independent lookup fail.
					// A later healthy connection must recover the original receipt.
					recovered, err := s.reconcileInvestigation(testContext(t), c)
					if err != nil {
						t.Fatal("later healthy reconciliation failed")
					}
					checkAdvanced(t, s, run, recovered)
					accepted := snapshotFor(t, s, run)
					replay, err := s.advanceInvestigation(testContext(t), c)
					if err != nil || replay != recovered || snapshotFor(t, s, run) != accepted {
						t.Fatal("recovery created another logical transition")
					}
					t.Log("RF-10: genuine COMMIT response lost plus proxy outage; immediate UNKNOWN; later independent lookup recovered original receipt; replay unchanged")
				}
			}
		})
	}
}
