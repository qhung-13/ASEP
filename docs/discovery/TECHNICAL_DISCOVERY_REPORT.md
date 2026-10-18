# ASEP — Technical Discovery Report v0.1

**Project:** ASEP — Agentic Software Engineering Platform  
**Milestone:** M00 — Architecture & Foundation  
**Ticket:** M00-02 — Technical Discovery & Architecture Constraints  
**Document ID:** ASEP-M00-02-TDR-001  
**Version:** 0.1  
**Approval Date:** 2026-10-16  
**Document Status:** OWNER APPROVED  
**Dependency:** M00-01 — Project Vision & MVP Scope (COMPLETED)  
**Primary Scope:** M01 — AI Merge Request Reviewer

---

## 1. Executive Summary

### 1.1. Purpose

Tài liệu này tổng hợp kết quả Technical Discovery của ASEP, nhằm xác định những điều kiện kỹ thuật, khả năng tích hợp, giới hạn môi trường và ràng buộc bảo mật ảnh hưởng đến quá trình thiết kế kiến trúc hệ thống.

Mục tiêu của M00-02 không phải lựa chọn technology stack hoặc triển khai ứng dụng, mà là xây dựng **Verified Technical Constraints Baseline** làm cơ sở cho các Architecture Decision Records (ADRs) và những ticket implementation tiếp theo.

Technical Discovery tập trung vào năm lĩnh vực:

1. Repository & Development Environment.
2. Ubuntu Hardware & Local AI Feasibility.
3. GitLab Integration Feasibility.
4. Security & Operational Constraints.
5. Architecture Decision Inputs & Downstream Risks.

### 1.2. Overall Findings

Kết quả investigation cho thấy:

- Máy Ubuntu có CPU, RAM, GPU, VRAM và storage phù hợp về mặt cấu hình để bắt đầu phát triển một hệ thống self-hosted AI.
- Docker Engine và Docker Compose đã được cài đặt và có thể truy cập.
- GitLab.com Free cung cấp các API cần thiết về mặt tài liệu cho workflow AI Merge Request Reviewer.
- Các chính sách liên quan đến authorization, confidentiality, publication safety, minimal persistence, retention và operational recovery đã được Owner phê duyệt.
- ASEP repository chưa được khởi tạo.
- GitLab API runtime, GPU container execution và local LLM performance chưa được kiểm chứng.

**Overall Assessment:** ASEP có đủ technical evidence ban đầu để chuyển sang Architecture Design, với điều kiện những rủi ro chưa xác minh phải tiếp tục được theo dõi và giải quyết tại các ticket phù hợp.

### 1.3. Verification Classification

Báo cáo sử dụng các mức độ bằng chứng sau:

| Classification | Meaning |
|---|---|
| VERIFIED | Có bằng chứng kỹ thuật trực tiếp từ command output hoặc runtime investigation |
| OWNER-CONFIRMED | Thông tin do Owner trực tiếp xác nhận |
| DOCUMENTED | Được hỗ trợ bởi tài liệu kỹ thuật chính thức, chưa kiểm thử thực tế |
| OWNER APPROVED | Quyết định hoặc policy đã được Owner phê duyệt |
| NOT VERIFIED | Chưa có đủ bằng chứng xác minh |
| OPEN | Rủi ro, câu hỏi hoặc vấn đề chưa được giải quyết |

Các mức độ này không được sử dụng thay thế cho nhau.

Một capability được ghi nhận `DOCUMENTED` không đồng nghĩa đã được `RUNTIME VERIFIED`. Tương tự, một policy được `OWNER APPROVED` không đồng nghĩa đã được implemented hoặc tested.

---

## 2. Repository & Development Environment Discovery

### 2.1. Repository Status

Owner xác nhận ASEP chưa được khởi tạo dưới dạng Git repository tại thời điểm thực hiện M00-02.

| Item | Finding | Classification |
|---|---|---|
| ASEP repository | Not created | OWNER-CONFIRMED |
| Git remote | Not applicable | OWNER-CONFIRMED |
| Current branch | Not applicable | OWNER-CONFIRMED |
| Existing application source | Not created | OWNER-CONFIRMED |
| `PROJECT_VISION.md` | Approved outside repository | OWNER-CONFIRMED |
| `M01_MVP_SCOPE.md` | Approved outside repository | OWNER-CONFIRMED |
| Authority documents on disk | Not inspected | NOT VERIFIED |

**Constraint:** Không giả định ASEP đã có repository, project structure hoặc application implementation.

Repository initialization, source code organization và authority document synchronization phải được thực hiện ở ticket phù hợp sau này.

### 2.2. Operating System

| Property | Verified Value |
|---|---|
| Distribution | Ubuntu |
| Version | 26.04.1 LTS |
| Codename | Resolute Raccoon |
| Linux Kernel | 7.0.0-38-generic |
| Architecture | x86_64 |

**Evidence:** `/etc/os-release`, `uname -r`.

### 2.3. Development Tooling

| Component | Version | Verification |
|---|---|---|
| Git | 2.53.0 | VERIFIED |
| Node.js | 24.21.0 | VERIFIED |
| npm | 11.19.0 | VERIFIED |
| Corepack | 0.36.0 | VERIFIED |
| Python | 3.14.4 | VERIFIED |
| pip | 25.1.1 | VERIFIED |
| GCC | 15.2.0 | VERIFIED |
| GNU Make | 4.4.1 | VERIFIED |
| VS Code | 1.141.1 | VERIFIED |
| pnpm | Corepack shim detected | NOT VERIFIED |
| Yarn | Corepack shim detected | NOT VERIFIED |

Khi kiểm tra pnpm và Yarn, Corepack đưa ra thông báo chuẩn bị tải package. Vì vậy, khả năng thực thi hai package managers này chưa được xác nhận.

Việc các công cụ được cài đặt không mặc nhiên chứng minh chúng tương thích với một technology stack cụ thể.

### 2.4. Docker & Container Runtime

| Component | Finding | Verification |
|---|---|---|
| Docker Engine | 29.9.0 | VERIFIED |
| Docker Client/Server communication | Successful | VERIFIED |
| Docker Compose | v5.6.0 | VERIFIED |
| Docker API | 1.56 | VERIFIED |
| containerd | v2.4.1 | VERIFIED |
| runc | 1.5.2 | VERIFIED |
| Default container runtime | runc | VERIFIED |
| Container workload execution | Not tested | NOT VERIFIED |
| NVIDIA GPU inside containers | Not tested | NOT VERIFIED |

**Evidence:** `docker version`, `docker compose version`, `docker info`.

Docker Engine đang hoạt động và Docker Client có thể truy cập Docker Server.

Tuy nhiên, chưa có container workload được thực thi trong phạm vi M00-02. Docker Compose deployment và GPU passthrough vì thế vẫn cần được kiểm chứng.

### 2.5. Environment Assessment

Môi trường phát triển hiện tại cung cấp những công cụ nền tảng cần thiết để bắt đầu triển khai ASEP.

Không có bằng chứng cho thấy cần thay đổi hệ điều hành hoặc tái cấu trúc storage trước khi bước vào Architecture Design.

Tuy nhiên, lựa chọn development tooling và infrastructure dependencies cuối cùng vẫn phụ thuộc vào ADR và requirements cụ thể.

---

## 3. Ubuntu Hardware & Local AI Feasibility

### 3.1. Hardware Inventory

| Component | Verified Configuration |
|---|---|
| CPU | AMD Ryzen 5 5600 |
| Physical cores | 6 |
| Logical threads | 12 |
| CPU architecture | x86_64 |
| Instruction sets | AVX2, FMA, SSE4.2 |
| RAM | 30 GiB |
| Swap | 8 GiB |
| GPU | NVIDIA GeForce RTX 5060 Ti |
| VRAM | 16,311 MiB |
| NVIDIA Driver | 595.99.02 |
| CUDA driver support | 13.2 |
| Filesystem | `/dev/nvme0n1p2` |
| Filesystem capacity | 937G |
| Available storage | 856G |

**Evidence:** `lscpu`, `free -h`, `lspci`, `nvidia-smi`, `df -h "$HOME"`.

Các giá trị về available memory, storage và GPU utilization phản ánh thời điểm thu thập evidence, không phải dung lượng được bảo đảm luôn khả dụng.

### 3.2. CPU Assessment

AMD Ryzen 5 5600 cung cấp 6 physical cores và 12 logical threads.

CPU hỗ trợ AVX2 và FMA, là những instruction sets có thể được tận dụng bởi một số CPU inference runtimes.

Đánh giá sơ bộ:

- Có cơ sở phần cứng để phát triển backend và Web UI.
- Có tiềm năng chạy background workers và supporting services.
- Có khả năng thử nghiệm CPU-based inference.
- Chưa có benchmark về throughput, concurrency hoặc CPU inference latency.

Không được suy ra rằng 12 logical threads tương đương hiệu năng của 12 physical cores.

### 3.3. RAM & Storage Assessment

Hệ thống có 30 GiB RAM được Linux báo cáo cùng 8 GiB swap.

Tại thời điểm kiểm tra, khoảng 20 GiB RAM được báo cáo là available.

Filesystem có 856G dung lượng còn trống.

**Preliminary Finding:** Memory và storage capacity có tiềm năng đáp ứng nhu cầu development, Docker images, source code, database và AI model artifacts trong giai đoạn đầu.

Tuy nhiên:

- Chưa đo actual memory consumption của ASEP.
- Chưa đo storage throughput.
- Chưa đo hiệu năng khi nhiều services hoạt động đồng thời.
- Swap không được xem là giải pháp thay thế tương đương RAM/VRAM để bảo đảm inference performance.

### 3.4. GPU & VRAM Assessment

RTX 5060 Ti được Ubuntu nhận diện và NVIDIA Driver hoạt động.

`nvidia-smi` xác nhận GPU có 16,311 MiB VRAM cùng driver version 595.99.02.

CUDA version 13.2 được NVIDIA driver báo hỗ trợ. Thông tin này không chứng minh CUDA Toolkit hoặc một AI inference runtime đã được cài đặt.

Đối với ASEP M01, GPU có tiềm năng hỗ trợ:

- Quantized local language models.
- GPU-accelerated code analysis.
- MR diff và relevant source-context processing.
- Experimentation với model/context configurations khác nhau.

Tuy nhiên, dung lượng VRAM phải phục vụ model weights, KV cache và runtime overhead.

Không thể kết luận model capacity hoặc maximum usable context window chỉ từ VRAM.

### 3.5. GPU Container Constraints

Docker đang sử dụng runtime `runc`.

Investigation không tìm thấy `nvidia-ctk` hoặc `nvidia-container-cli` trong PATH, đồng thời không thấy NVIDIA runtime được đăng ký trong Docker runtime listing.

**Finding ENV-001 — GPU Container Access: NOT VERIFIED**

Chưa có bằng chứng GPU có thể được sử dụng bởi một Docker container.

Không kết luận NVIDIA Container Toolkit hoàn toàn chưa được cài đặt chỉ từ kết quả PATH inspection.

### 3.6. Local AI Feasibility Conclusion

Hardware có tiềm năng hỗ trợ local inference, nhưng M00-02 chưa xác nhận:

- Local AI runtime compatibility.
- GPU model execution.
- Tokens per second.
- Time to first token.
- Context-window memory consumption.
- Review latency trên MR thực tế.
- Model accuracy và actionable finding quality.
- Resource contention giữa inference và application services.

**Conclusion:** Local AI feasibility là promising nhưng chưa benchmarked.

Không lựa chọn AI model, quantization strategy hoặc inference framework chính thức trong M00-02.

---

## 4. GitLab Integration Feasibility

### 4.1. Integration Target

Owner xác nhận:

| Property | Decision |
|---|---|
| Platform | GitLab.com |
| Subscription | Free |
| Account | Personal account |
| Primary integration | M01 AI Merge Request Reviewer |
| Review trigger | Manual / On-demand |
| Publication | Human approval required |
| Inference location | Self-hosted ASEP |

ASEP không cần self-host GitLab Server trong M01.

Kết nối với GitLab.com qua API là một ngoại lệ nghiệp vụ được cho phép trong định hướng self-hosted, local-only AI inference.

### 4.2. GitLab REST API Feasibility Matrix

| Capability | Relevant API | Verification |
|---|---|---|
| Read Merge Request metadata | Merge Requests API | DOCUMENTED |
| Retrieve MR diffs | MR Diffs API | DOCUMENTED |
| Retrieve raw diffs | Raw Diffs API | DOCUMENTED |
| Retrieve MR versions | MR Versions API | DOCUMENTED |
| Retrieve repository files | Repository Files API | DOCUMENTED |
| Publish general MR notes | Notes API | DOCUMENTED |
| Publish inline findings | Discussions API | DOCUMENTED |
| Read existing feedback | Notes / Discussions APIs | DOCUMENTED |
| PAT-based authentication | Access Tokens documentation | DOCUMENTED |
| Real authenticated requests | Not executed | NOT VERIFIED |
| Real inline publication | Not executed | NOT VERIFIED |
| End-to-end publication safety | Not tested | NOT VERIFIED |

Các GitLab capabilities trên đã được đối chiếu với tài liệu chính thức tại thời điểm discovery.

Việc tồn tại API endpoint không bảo đảm tài khoản, token permissions, target project hoặc mọi loại MR diff đều hỗ trợ thao tác mong muốn trong runtime.

### 4.3. Authentication & Token Permissions

Fine-grained Personal Access Token được tài liệu GitLab ghi nhận là một candidate có thể giới hạn permissions và resource scope.

GitLab.com Free không cung cấp Project Access Tokens theo plan restrictions được đối chiếu trong investigation.

Các hoạt động M01 cần quyền tương ứng để:

- Đọc MR metadata.
- Đọc MR diff và versions.
- Đọc source-code context.
- Đọc existing comments/discussions.
- Tạo general MR notes.
- Tạo inline discussions.

**Finding AUTH-001 — Least-Privilege Permissions**

Một số publication permissions có thể cấp nhiều quyền hơn thao tác đăng comment đơn thuần.

Do đó, permission design phải xem xét đồng thời:

1. Token permissions.
2. Permissions của GitLab account.
3. Repository/project access.
4. Application-level authorization.
5. Allowed GitLab API operations.

Không coi Fine-grained PAT là giải pháp least-privilege đã được runtime-verified.

### 4.4. Runtime Verification Blocker

Owner đã đăng nhập GitLab.com nhưng tài khoản yêu cầu hoàn tất onboarding trước khi truy cập Personal Access Tokens UI.

Các trường onboarding yêu cầu thông tin Company, Group, Project và thông tin sử dụng.

Để tránh tạo GitLab resources ngoài phạm vi discovery, Owner và assistant thống nhất không tiếp tục onboarding tại thời điểm đó.

Kết quả:

- Không tạo Personal Access Token.
- Không tạo test repository.
- Không gọi authenticated API.
- Không publish comment.
- Không thực hiện thao tác ghi lên GitLab.

**Status:** GitLab API Runtime Verification — NOT VERIFIED.

### 4.5. Diff Completeness

GitLab documentation ghi nhận những giới hạn liên quan đến MR diff retrieval.

Một số diffs có thể bị collapsed, omitted hoặc vượt giới hạn kích thước.

**Finding API-001 — Incomplete Review Input**

ASEP không được tuyên bố review coverage đầy đủ khi không có đủ input.

Architecture Design phải xem xét:

- Diff completeness detection.
- Missing context tracking.
- Handling collapsed/oversized diffs.
- Explicit review limitations.
- Prevention of misleading review-complete claims.

### 4.6. Stale Review Protection

GitLab cung cấp MR version identifiers và commit SHAs để đối chiếu review input với trạng thái MR.

Điều này tạo cơ sở triển khai stale-review protection.

Tuy nhiên, tồn tại race condition giữa:

1. ASEP kiểm tra MR version.
2. Developer push commit mới.
3. ASEP gửi publication request.

**Finding API-002 — Publication Race Condition**

Version revalidation giúp giảm rủi ro nhưng chưa chứng minh loại bỏ hoàn toàn race condition.

Chưa có runtime evidence về cơ chế atomic conditional publication phù hợp với toàn bộ required operations.

### 4.7. Publication Constraints

M01 phải tuân thủ những publication policies đã được phê duyệt:

- Human approval before publishing.
- Selective finding approval.
- Một MR summary theo approved publication policy.
- Chỉ publish nội dung đã được Owner phê duyệt.
- Inline comments phải có valid diff positions.
- Không tự động fallback sang general notes khi inline position không hợp lệ.
- Ghi nhận partial publication failures.
- Ngăn stale và duplicate publication.
- Không tự động xóa hoặc resolve GitLab discussions cũ.
- Không tự động merge.

Rate limits, retry behavior, invalid positions và response reconciliation cần được kiểm thử trong runtime.

### 4.8. GitLab Feasibility Conclusion

GitLab.com Free cung cấp documented API capabilities phù hợp với các thao tác cốt lõi của M01.

Tuy nhiên, authentication permissions, publication edge cases và end-to-end behavior vẫn chưa được runtime-verified.

**Conclusion:** Documentation-level feasibility established; runtime feasibility remains open.

---

## 5. Security Constraints Baseline

### 5.1. Trust Boundaries

ASEP M01 có các security boundaries chính:

| Boundary | Data / Interaction | Security Concern |
|---|---|---|
| GitLab.com → ASEP | MR metadata, diff, source context | Untrusted inputs, credentials, confidentiality |
| ASEP → Local AI | Review context và model inputs | Prompt injection, data exposure |
| Owner/Viewer → Web UI | Authentication, review access | Authorization, information disclosure |
| ASEP → Persistence | Findings, approvals, publication records | Access control, retention |
| ASEP → GitLab.com | Approved publication requests | Stale data, duplicates, unauthorized operations |
| ASEP → Logs/Audit | Operational history | Sensitive-data leakage |

GitLab.com là external integration service.

Local AI inference phải hoạt động trong phạm vi self-hosted ASEP.

**Local-only inference không đồng nghĩa offline operation.**

### 5.2. Authentication & Authorization

Approved constraints:

- Accounts được Owner quản lý.
- Không public self-registration.
- MVP có Owner và Viewer roles.
- Repository-scoped authorization.
- Default-deny access control.
- Viewer chỉ được truy cập dữ liệu thuộc repositories được cấp quyền.
- Viewer không được xem unapproved findings.
- Viewer chỉ xem shareable summaries và approved/published findings theo policy.
- Backend phải thực thi authorization; frontend restrictions không đủ.

Không có authentication framework nào được lựa chọn trong M00-02.

### 5.3. Source Code Confidentiality

Approved security direction:

- Source code inference phải diễn ra cục bộ.
- Không gửi source code đến external AI providers.
- GitLab credentials không được đưa vào model prompts.
- Sensitive data không được xuất hiện trong operational logs.
- Chỉ persist source-derived data thật sự cần thiết.
- Review evidence có chứa code snippets cũng phải được bảo vệ.

Repository files, diffs và MR descriptions phải được xem là untrusted inputs, không phải instructions có thẩm quyền.

### 5.4. Prompt Injection & Tool Execution

MR descriptions và source files có thể chứa nội dung cố tình hướng dẫn AI bỏ qua security constraints.

M01 phải giữ ranh giới giữa:

- Workflow orchestration.
- Model reasoning.
- Authorized application operations.

M01 không cho phép AI tự thực thi shell commands, sửa source code, push commits, approve MR hoặc merge MR.

Mọi GitLab publication phải đi qua human approval và application-enforced controls.

### 5.5. Network Egress — SEC-009

**Status: OWNER APPROVED**

- Network egress giới hạn cho các GitLab integrations và local services cần thiết.
- Không sử dụng external AI inference.
- Không external telemetry theo mặc định.
- Không mở rộng outbound connectivity chỉ vì một framework hoặc monitoring tool có hỗ trợ.
- Những nhu cầu network mới phải được đánh giá theo security requirements.

Chi tiết enforcement mechanism chưa được lựa chọn hoặc kiểm thử.

### 5.6. Web UI Access Boundary

Owner xác nhận:

**M01 Initial Deployment**

- ASEP Web UI chỉ truy cập qua localhost trên Ubuntu PC.
- Không chủ động expose Web UI công khai ra Internet.

**Future Deployment Direction**

- Có thể sử dụng private domain.
- Giới hạn kết nối qua LAN hoặc VPN.
- Pritunl là ví dụ một giải pháp VPN có thể xem xét, không phải technology decision đã được chốt.

VPN access không thay thế application authentication và authorization.

DNS, reverse proxy, TLS và VPN infrastructure nằm ngoài phạm vi implementation của M00-02.

---

## 6. Data Classification & Retention Baseline

### 6.1. Minimal Persistence

**Status: OWNER APPROVED**

ASEP M01 áp dụng định hướng Minimal Persistence.

Dữ liệu tạm thời:

- Repository source-code context.
- Raw Merge Request diffs.
- Temporary inference inputs.
- Temporary job artifacts.

Dữ liệu có thể persist theo policy:

- Review identifiers và metadata.
- MR version/commit references.
- Candidate review findings.
- Minimum supporting evidence.
- Approval state.
- Publication state và outcomes.
- Minimal reconciliation ledger.
- Sanitized audit/operational metadata.

Không lưu toàn bộ repository hoặc full review source snapshot lâu dài theo mặc định.

### 6.2. Approved Retention Matrix

| ID | Data Category | Approved Retention |
|---|---|---|
| RET-001 | Unapproved findings | 30 ngày không hoạt động |
| RET-002 | Approved-but-unpublished findings | 30 ngày không hoạt động |
| RET-003 | Published finding content/evidence | 90 ngày sau successful publication |
| RET-004 | Sanitized audit events | 90 ngày |
| RET-005 | Minimal publication/reconciliation ledger | 365 ngày, sau đó xóa theo policy |
| RET-006 | Sanitized operational logs | 30 ngày |
| RET-007 | Expired local finding content/evidence | Xóa khi hết hạn; giữ minimal audit marker theo TTL riêng |
| RET-008 | Inactivity reset | Chỉ meaningful Owner actions |

**Approval:** OWNER APPROVED.

### 6.3. Expiration Semantics

Unapproved findings và approved-but-unpublished findings hết hạn sau 30 ngày không có hoạt động theo policy.

Những hành động không reset inactivity timer:

- Page views.
- Page reloads.
- Background polling.
- Các thao tác đọc không có ý nghĩa thay đổi review state.

Những hoạt động có ý nghĩa của Owner có thể được sử dụng để xác định review activity theo rule triển khai sau này.

Khi finding hết hạn:

- Finding không còn được approve/publish.
- Approval chưa publish không còn hiệu lực.
- Local finding content/evidence phải được cleanup.
- Chỉ minimal audit marker được giữ theo TTL áp dụng.

Chi tiết timestamp, TTL scheduling và deletion mechanism là implementation concerns chưa được quyết định trong M00-02.

### 6.4. Published Findings

Published finding content/evidence được giữ local trong 90 ngày kể từ successful publication.

Publication metadata có thể được giữ độc lập trong minimal ledger theo RET-005.

Việc xóa local records không đồng nghĩa xóa GitLab comments.

ASEP không tự động xóa hoặc resolve published GitLab threads chỉ vì local retention đã hết hạn.

### 6.5. Reproducibility vs Confidentiality

Minimal Persistence giảm lượng source code nhạy cảm được lưu trữ, nhưng hạn chế khả năng reproduce chính xác toàn bộ review input trong tương lai.

Các dữ liệu như MR version identifiers, model identifiers, findings và minimum evidence có thể hỗ trợ traceability.

Tuy nhiên, không được cam kết deterministic AI reproduction nếu không có phương pháp kiểm chứng riêng.

Đây là một engineering trade-off được ghi nhận cho Architecture Design.

---

## 7. Operational Constraints Baseline

### 7.1. Approved Operational Policies

| ID | Policy | Status |
|---|---|---|
| OPS-001 | Cleanup temporary data sau completion, cancellation và failure; xử lý dữ liệu tồn dư sau crash | OWNER APPROVED |
| OPS-002 | Bounded safe retries, MR-version revalidation; không automatic publication | OWNER APPROVED |
| OPS-003 | Tối đa một active review và một publication operation trên mỗi MR tại một thời điểm | OWNER APPROVED |
| OPS-004 | Integration removal xóa local credential, hủy jobs, xóa pending content; giữ published history theo TTL | OWNER APPROVED |

### 7.2. Job Lifecycle & Recovery

Background operations cần được thiết kế với:

- Explicit execution states.
- Timeouts.
- Cancellation.
- Bounded retries.
- Failure recording.
- Recoverable states.
- Idempotency protection.

Nếu job thất bại hoặc host bị restart, ASEP phải có khả năng xác định trạng thái job và xử lý temporary artifacts còn tồn tại.

Không được phụ thuộc vào việc source code tạm thời vẫn tồn tại sau crash.

### 7.3. Publication Recovery

Publication retries phải phân biệt:

- Request chưa được gửi.
- Request bị từ chối rõ ràng.
- Request có kết quả không chắc chắn.
- Request thành công.
- Request thành công một phần.

Những trường hợp uncertain outcome cần reconciliation trước khi quyết định retry.

Không được mặc định gửi lại publication request vì có nguy cơ tạo duplicate comments.

**Owner approval không cho phép automatic publication.**

### 7.4. Concurrency

Theo OPS-003, mỗi MR chỉ được có tối đa:

- Một active review operation.
- Một active publication operation.

Cơ chế lock, transaction, lease hoặc queue serialization cụ thể chưa được quyết định.

Concurrency controls phải hỗ trợ cancellation, recovery và tránh duplicate execution.

### 7.5. Integration Removal

Khi GitLab integration bị remove:

- Local credential phải được xóa.
- Active jobs liên quan phải được hủy theo policy.
- Pending review content phải được cleanup.
- Published history được giữ theo retention TTL tương ứng.
- Không tự động xóa GitLab comments đã publish.

### 7.6. Backup & Restore

Automated backup implementation được defer sang milestone phù hợp sau M01.

Quyết định defer không đồng nghĩa backup/recovery risk đã được giải quyết.

Architecture Design phải ghi nhận những giới hạn liên quan đến data loss và recovery nếu chưa có backup.

### 7.7. Observability

Observability cần hỗ trợ:

- Review/job execution status.
- Tool/API failure reporting.
- Publication result tracking.
- Security-relevant audit events.
- Sanitized operational logs.
- Cleanup and recovery diagnostics.

Không lựa chọn logging stack, metrics backend hoặc tracing framework chính thức trong M00-02.

Operational logs phải tuân thủ RET-006, audit events tuân thủ RET-004 và network telemetry tuân thủ SEC-009.

---

## 8. Architecture Decision Inputs

M00-02 chỉ tổng hợp inputs và trade-offs cho Architecture Design.

Không có lựa chọn cuối cùng nào về application framework, database, queue hoặc AI runtime được chốt trong ticket này.

### 8.1. Candidate Decision Matrix

| Decision Area | Candidate Alternatives | Key Considerations |
|---|---|---|
| Application architecture | Modular monolith / distributed services | Complexity, maintainability, module boundaries |
| Frontend | Next.js / alternatives | Web UI, approval workflow, authorization |
| Backend | NestJS / alternatives | API design, GitLab integration, security |
| Job execution | In-process / separate workers | Long-running inference, recovery, concurrency |
| Persistence | PostgreSQL / alternatives | Durable review state, retention, reconciliation |
| Queue infrastructure | Redis + BullMQ / alternatives | Retries, scheduling, operational overhead |
| AI runtime | Local inference frameworks | GPU compatibility, latency, context management |
| Execution environment | Host-native / containerized | GPU access, security isolation, operations |
| Authentication | Application-managed identity alternatives | Owner/Viewer, localhost-first |
| Deployment | Localhost / future private network | Security boundary, portability |
| Observability | Structured logs, metrics, tracing alternatives | Sanitization, retention, operational visibility |

### 8.2. Architectural Invariants

Kiến trúc M01 phải bảo toàn những yêu cầu sau:

1. Workflow orchestration độc lập về trách nhiệm với model reasoning và tool execution.
2. AI không được tự vượt qua application authorization.
3. Consequential operations phải có human approval.
4. Publication phải dựa trên approved findings và MR freshness.
5. Source code inference phải chạy cục bộ.
6. Sensitive source data phải được bảo vệ và giới hạn retention.
7. Jobs phải có trạng thái rõ ràng, recoverability và controlled retries.
8. Data access phải được kiểm soát theo repository.
9. Publication phải hỗ trợ partial-failure reporting và reconciliation.
10. Application architecture không được giả định mọi external API call đều thành công.

Đây là requirements-derived constraints, không phải phê duyệt cho một implementation pattern cụ thể.

### 8.3. Architecture Design Guidance

Modular monolith là một candidate phù hợp để đánh giá cho project cá nhân vì có thể giảm operational complexity trong giai đoạn đầu.

Tuy nhiên, quyết định chia application modules, workers và infrastructure phải dựa trên requirements và trade-off review chính thức.

Không chọn microservices, orchestration frameworks hoặc additional infrastructure chỉ để tăng độ phức tạp hay giá trị trình bày trong portfolio.

---

## 9. Risks, Assumptions & Open Questions Register

### 9.1. Remaining Risks

| ID | Risk / Open Question | Status |
|---|---|---|
| R-01 | GitLab onboarding và minimum PAT permissions chưa kiểm thử | OPEN |
| R-02 | Stale publication race condition chưa được giải quyết bằng runtime evidence | OPEN |
| R-03 | MR diff có thể incomplete hoặc vượt API limits | OPEN |
| R-04 | GPU access inside Docker containers chưa kiểm chứng | OPEN |
| R-05 | Local AI latency, context capacity và review quality chưa benchmarked | OPEN |
| R-06 | Crash cleanup và job recovery chưa được thực nghiệm | OPEN |
| R-07 | Duplicate detection/reconciliation sau publication ledger TTL chưa xác minh | OPEN |
| R-08 | Exact inactivity semantics và TTL enforcement chưa được thiết kế/kiểm thử | OPEN |
| R-09 | Automated backup/restore chưa triển khai | OPEN |
| R-10 | Prompt injection, authorization và confidentiality controls chưa được security-tested | OPEN |

### 9.2. Unverified Assumptions

Các giả định sau không được coi là facts đã xác minh:

- Fine-grained PAT thực tế đáp ứng toàn bộ required permissions của M01.
- GitLab inline discussion publication hoạt động đúng với tài khoản và repositories dự kiến.
- Stale-review protection có thể đạt các safety guarantees đã phê duyệt bằng phương án kỹ thuật được lựa chọn.
- Local inference đáp ứng latency và quality thresholds.
- Selected GPU runtime hoạt động ổn định trên RTX 5060 Ti.
- Containerized GPU inference hoạt động trên Docker environment hiện có.
- Selected persistence/queue design đáp ứng recovery và concurrency requirements.
- Retention và deletion được thực thi chính xác ngay cả khi job failure hoặc system crash.

### 9.3. Risk Handling

Những rủi ro trên phải được:

- Giữ ở trạng thái OPEN cho đến khi có evidence phù hợp.
- Gắn với downstream architecture, implementation hoặc validation work.
- Đánh giá theo tác động và mức độ ưu tiên thực tế.
- Không tự động được xem là resolved khi một ADR hoặc policy được phê duyệt.

---

## 10. Acceptance Criteria Assessment

| AC | Acceptance Criterion | Assessment |
|---|---|---|
| AC-01 | Environment Inventory | Prepared with direct terminal evidence |
| AC-02 | Repository Discovery | Owner-confirmed not created; filesystem not inspected |
| AC-03 | Hardware Constraints | Hardware inventoried; inference performance not verified |
| AC-04 | GitLab Feasibility | Official documentation investigated; runtime gaps documented |
| AC-05 | Security Constraints | Consolidated and Owner approved |
| AC-06 | Architecture Inputs | Candidate alternatives, trade-offs and risks prepared |
| AC-07 | Review & Approval | OWNER APPROVED |

Owner phê duyệt Technical Discovery Report v0.1 với những rủi ro và unverified items được công khai ghi nhận.

Approval không thay thế runtime validation.

---

## 11. Deliverables

| Deliverable | Result |
|---|---|
| Technical Discovery Report v0.1 | OWNER APPROVED |
| Environment & Hardware Inventory | PREPARED |
| GitLab API Feasibility Matrix | PREPARED — DOCUMENTATION-BASED |
| Security & Operational Constraints | OWNER APPROVED |
| Verified Facts / Assumptions / Open Questions Register | PREPARED |
| Architecture Decision Inputs & Downstream Handoff | PREPARED |

Các deliverables được tổng hợp trong tài liệu này.

ASEP repository chưa được tạo tại thời điểm investigation; không có bằng chứng các deliverables đã được commit vào repository.

---

## 12. Downstream Handoff

### 12.1. Inputs for Architecture Design

Architecture Design phải sử dụng:

- M00-01 Requirements Baseline v0.1.
- Approved publication, security, reliability và evaluation constraints.
- Technical Discovery Report v0.1.
- Approved Minimal Persistence và retention policies.
- Environment & Hardware Inventory.
- GitLab API Feasibility Matrix.
- Open Risks & Unverified Assumptions Register.

### 12.2. Prioritized Validation Dependencies

Các nhóm cần được phân bổ vào downstream work:

**GitLab Integration**

- Authentication permissions.
- MR diff completeness.
- MR version and SHA validation.
- Inline position behavior.
- Publication failures, retries và reconciliation.

**Local AI**

- Inference runtime compatibility.
- GPU acceleration.
- Context-window resource consumption.
- Latency measurements.
- Baseline-first AI review evaluation.

**Workflow Reliability**

- State recovery.
- Concurrent review/publication operations.
- Idempotency.
- Cancellation and timeout behavior.
- Temporary-data cleanup.

**Security & Data Management**

- Repository-scoped authorization.
- Sensitive logging controls.
- Prompt-injection resistance.
- Credential handling.
- Retention enforcement.
- Safe deletion and recovery limitations.

### 12.3. Recommended Next Action

Chuyển M00-02 Execution Summary về cuộc trò chuyện **ASEP — Project Control & Roadmap**.

Project Control chịu trách nhiệm:

1. Đánh giá execution evidence và approved deliverables.
2. Quản lý official ticket status.
3. Theo dõi unresolved risks.
4. Phân bổ downstream validation work.
5. Xác định ticket tiếp theo theo approved roadmap.

Báo cáo này không tự thay đổi roadmap hoặc official ticket status.

---

## 13. Final Conclusion & Approval

Technical Discovery Report v0.1 xác lập baseline ban đầu về:

- Development environment.
- Hardware và local AI feasibility.
- GitLab.com Free API capabilities.
- Security trust boundaries.
- Data confidentiality và retention.
- Operational reliability constraints.
- Architecture decision inputs.
- Risks và outstanding validation requirements.

Technical Discovery xác nhận môi trường Ubuntu có tiềm năng phục vụ việc phát triển một nền tảng self-hosted Agentic Software Engineering.

GitLab.com Free cung cấp các documented API capabilities phù hợp với workflow M01, nhưng các thao tác runtime quan trọng vẫn phải được kiểm chứng.

Security và operational policies đã được Owner phê duyệt tạo thành các ràng buộc bắt buộc cho Architecture Design.

**Không có final technology stack, AI model, database, queue, application architecture hoặc deployment topology nào được lựa chọn trong M00-02.**

Không có ASEP repository được khởi tạo, application code được triển khai hay GitLab publication request được thực thi trong ticket này.

### Owner Approval Record

| Field | Value |
|---|---|
| Document | ASEP — Technical Discovery Report |
| Version | 0.1 |
| Review Result | APPROVED |
| Approved By | Project Owner |
| Approval Date | 2026-10-16 |
| Open Risks | Accepted as explicitly documented, not resolved |
| Runtime Verification | Outstanding items preserved |
| Architecture Decisions | NOT FINALIZED |

**Final Document Status: OWNER APPROVED — BASELINE v0.1**

*End of Technical Discovery Report v0.1.*