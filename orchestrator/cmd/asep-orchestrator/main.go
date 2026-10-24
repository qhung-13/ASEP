package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"regexp"
	"strings"
	"syscall"
	"time"

	"github.com/qhung-13/ASEP/orchestrator/internal/httpapi"
	"github.com/qhung-13/ASEP/orchestrator/internal/workflow"
)

func main() {
	if len(os.Args) == 1 {
		fmt.Println("ASEP Orchestrator bootstrap OK")
		return
	}
	if len(os.Args) != 2 || os.Args[1] != "serve" {
		fmt.Fprintln(os.Stderr, "usage: asep-orchestrator [serve]")
		os.Exit(2)
	}
	if err := serve(); err != nil {
		fmt.Fprintln(os.Stderr, "orchestrator startup/shutdown failure:", err)
		os.Exit(1)
	}
}

func readPrivateDevToken(path string) (string, error) {
	if path == "" {
		return "", errors.New("token file required")
	}
	info, err := os.Lstat(path)
	if err != nil {
		return "", errors.New("token file unavailable")
	}
	if !info.Mode().IsRegular() || info.Mode().Perm()&0o077 != 0 {
		return "", errors.New("token file must be private regular file")
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return "", errors.New("token file unreadable")
	}
	token := strings.TrimSpace(string(data))
	if !regexp.MustCompile(`^[0-9a-f]{64}$`).MatchString(token) {
		return "", errors.New("invalid dev token format")
	}
	return token, nil
}

func serve() error {
	addr := os.Getenv("ASEP_ORCHESTRATOR_BIND")
	host, port, err := net.SplitHostPort(addr)
	if err != nil || host != "127.0.0.1" || port == "" || port == "0" {
		return errors.New("loopback binding required")
	}
	token, err := readPrivateDevToken(os.Getenv("ASEP_ORCHESTRATOR_DEV_TOKEN_FILE"))
	if err != nil {
		return err
	}
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	store, err := workflow.Open(ctx, os.Getenv("ASEP_ORCHESTRATOR_DATABASE_URL"))
	cancel()
	if err != nil {
		return errors.New("orchestration database unavailable")
	}
	defer store.Close()
	handler, err := httpapi.New(store, os.Getenv("ASEP_ORCHESTRATOR_ALLOWED_SUBJECT_PREFIX"), token, logger)
	if err != nil {
		return err
	}
	listener, err := net.Listen("tcp", addr)
	if err != nil {
		return fmt.Errorf("bind local service: %w", err)
	}
	defer listener.Close()
	server := &http.Server{Handler: handler, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 10 * time.Second,
		WriteTimeout: 10 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 8192}
	signals, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, os.Interrupt)
	defer stop()
	go func() {
		<-signals.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = server.Shutdown(shutdownCtx)
	}()
	logger.Info("service_ready", "bind", "loopback", "api_mode", "synthetic_only")
	err = server.Serve(listener)
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
}
