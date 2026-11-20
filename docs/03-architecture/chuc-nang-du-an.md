# Tổng quan chức năng dự án SmartDrone (Trạm Giao)

Tài liệu mô tả ngắn các chức năng đang có và các hàm chính ở **backend** (Spring Boot) và **web** (React + TypeScript). Chi tiết từng API nằm ở thư mục [05-api](../05-api/).

- Vai trò: `CUSTOMER` (khách hàng), `DISPATCHER` (điều phối), `STATION_OPERATOR` (nhân viên trạm), `LOGISTICS_MANAGER` (quản lý hậu cần), `SYSTEM_ADMIN` (quản trị viên).
- Đường dẫn API dưới đây không gồm tiền tố `/api` (context-path của backend).
- Mã `FR-xx` là mã yêu cầu trong SRS.

---

## 1. Backend

Cấu trúc theo lớp: `api` (controller, security, error) → `application` (service, DTO) → `domain` (entity, enum, luật nghiệp vụ) → `infrastructure` (repository, seed, dịch vụ ngoài).

### 1.1 Xác thực và tài khoản (FR-01, FR-02)

| Chức năng | API | Hàm chính | Mô tả |
|---|---|---|---|
| Đăng ký | `POST /v1/auth/register` | `AuthService.register` | Tạo tài khoản luôn có vai trò CUSTOMER, không nhận `role` từ client. Chuẩn hóa email về chữ thường, băm mật khẩu bằng BCrypt, báo 409 nếu email trùng (kể cả khi hai request chạy song song). Số điện thoại bắt buộc. |
| Đăng nhập | `POST /v1/auth/login` | `AuthService.login` | Kiểm tra mật khẩu, trả access token (JWT) và refresh token. Sai email hay sai mật khẩu đều trả cùng một lỗi, có băm giả để tránh dò email qua thời gian phản hồi. Tài khoản bị khóa chỉ bị báo sau khi mật khẩu đúng. |
| Làm mới token | `POST /v1/auth/refresh` | `AuthService.refresh` | Đổi refresh token lấy cặp mới, token cũ bị thu hồi. Nếu một token đã thu hồi bị dùng lại thì thu hồi toàn bộ phiên của người đó. |
| Đăng xuất | `POST /v1/auth/logout` | `AuthService.logout` | Thu hồi refresh token đang dùng, gọi lại nhiều lần vẫn thành công. |
| Hồ sơ cá nhân | `GET/PATCH /v1/me`, `POST /v1/me/password` | `UserProfileService.get / update / changePassword` | Xem và sửa họ tên, số điện thoại. Đổi mật khẩu phải nhập đúng mật khẩu hiện tại. |
| Quyền của tôi | `GET /v1/me/permissions` | `AccessController.myPermissions` | Trả vai trò và danh sách endpoint mà vai trò đó được gọi. |

Token và kiểm tra quyền:
- `AccessTokenIssuer / JwtAccessTokenIssuer.issue`: tạo JWT chứa `role` và `accessVersion`.
- `AccountJwtAuthenticationConverter.convert`: sau khi Spring kiểm tra chữ ký, hàm này đối chiếu lại với CSDL (tài khoản còn tồn tại, còn hoạt động, đúng vai trò, đúng `accessVersion`). Nhờ đó khóa tài khoản hay đổi vai trò làm token cũ mất hiệu lực ngay.
- `TokenHasher.sha256Hex / newOpaqueToken`: refresh token chỉ lưu dạng băm SHA-256.
- `RbacPolicy.rules`: bảng quyền theo method + đường dẫn, được `SecurityConfig` nạp thành luật `hasAnyRole`. Endpoint nào chưa khai báo thì bị từ chối (`denyAll`).

### 1.2 Quản trị hệ thống (FR-02, chỉ SYSTEM_ADMIN)

Quyền được kiểm tra hai lớp: `RbacPolicy` theo URL và `@PreAuthorize` ở service.

| Chức năng | API | Hàm chính | Mô tả |
|---|---|---|---|
| Danh sách tài khoản | `GET /v1/admin/accounts` | `AdminAccountService.list` | Tìm theo tên/email/SĐT (có thoát ký tự `%`, `_`), lọc theo vai trò và trạng thái, phân trang, mới nhất trước. |
| Tạo tài khoản | `POST /v1/admin/accounts` | `AdminAccountService.create` | Admin chọn vai trò và mật khẩu ban đầu (BCrypt). Email trùng báo 409. |
| Sửa tài khoản | `PUT /v1/admin/accounts/{id}` | `AdminAccountService.update` | Sửa họ tên, SĐT, vai trò. Đổi vai trò thì tăng `accessVersion` và thu hồi mọi phiên. |
| Khóa / mở khóa | `PATCH /v1/admin/accounts/{id}/status` | `AdminAccountService.setEnabled` | Khóa thì token cũ mất hiệu lực. Không cho tự khóa mình hay khóa quản trị viên cuối cùng. |
| Vai trò và quyền | `GET /v1/admin/roles`, `PUT /v1/admin/roles/{role}/permissions` | `AdminRoleService.catalog / updatePermissions` | Xem danh mục quyền theo nhóm và quyền của từng vai trò. Quyền của SYSTEM_ADMIN bị khóa (trả 409). |
| Cấu hình hệ thống | `GET/PUT /v1/admin/settings` | `AdminSettingsService.list / update` | Xem và lưu cấu hình. Kiểm tra kiểu và khoảng giá trị bằng `SettingDefinitions`, lưu một lần hoặc không lưu gì. |
| Nhật ký thao tác | (ghi vào bảng `audit_logs`) | `AuditService.record` | Ghi người làm, hành động, đối tượng và thời điểm cho mỗi thao tác quản trị. Nằm cùng transaction nên thao tác lỗi thì không ghi. |

Hàm hỗ trợ:
- `PermissionCatalog`: danh mục 15 quyền, quyền mặc định của từng vai trò, tên hiển thị.
- `AccessSettingsSeeder.run`: chạy mỗi lần khởi động, nạp quyền, quyền mặc định (chỉ lần đầu, không ghi đè chỉnh sửa của admin) và 6 cấu hình mặc định.
- `UserAccessService.update` và `GET /v1/users`, `GET /v1/roles`, `PATCH /v1/users/{id}/access`: bản quản trị truy cập đời trước, vẫn còn và vẫn được kiểm thử.

Lưu ý: bảng quyền hiện chỉ để cấu hình và hiển thị; các endpoint nghiệp vụ vẫn kiểm tra theo vai trò. Giá trị cấu hình chưa được module nghiệp vụ nào đọc.

### 1.3 Địa chỉ giao hàng (FR-03, CUSTOMER)

`AddressController` → `AddressService`:
- `list`: danh sách địa chỉ của chính khách, có phân trang.
- `create / update / delete`: thêm, sửa, xóa địa chỉ (kiểm tra quyền sở hữu ở mỗi thao tác).
- `snapshotForOrder`: lấy bản sao địa chỉ khi tạo đơn, để sau này sửa hay xóa địa chỉ không làm đổi thông tin đơn cũ.

### 1.4 Trạm (FR-07)

| Chức năng | API | Hàm chính | Mô tả |
|---|---|---|---|
| Danh sách trạm | `GET /v1/stations` | `StationService.list` | Mọi vai trò xem được. |
| Tạo / sửa trạm | `POST`, `PUT /v1/stations/{id}` | `StationService.create / update` | Chỉ quản lý hậu cần. Kiểm tra tọa độ và sức chứa. |
| Đổi trạng thái | `PATCH /v1/stations/{id}/status` | `StationService.changeStatus` | ACTIVE / MAINTENANCE / INACTIVE. Ghi lịch sử vào `StationStatusEvent` (ai, từ trạng thái nào sang nào, lúc nào). |
| Giám sát trạm | `GET /v1/stations/monitoring` | `StationMonitoringService.monitor` | Trả trạng thái, kết nối, chỗ đang giữ trong khung giờ hiện tại và số kiện theo từng nhóm. Nhân viên trạm chỉ thấy trạm được phân công (`StaffStationScope`). |
| Khả năng sẵn sàng | `GET /v1/stations/{id}/availability` | `StationAvailabilityService.get` | Danh sách khung giờ trong một ngày kèm số chỗ đã dùng, còn trống. |

Hàm hỗ trợ: `StationLoadRules` (quy tắc đếm kiện theo nhóm), `StationLoadRepository` (truy vấn số kiện), `DatabaseStaffStationScope` (phân công nhân viên cho trạm).

### 1.5 Đơn giao hàng (FR-04, FR-05, FR-06)

**Khách hàng**
- `OrderService.create / createIdempotent / replay`: tạo đơn kèm kiện hàng. Có header `Idempotency-Key` thì gửi lại cùng nội dung sẽ trả đơn cũ, khác nội dung thì 409. `OrderRequestFingerprint.of` băm nội dung bằng SHA-256, `IdempotencyKeys.require` kiểm tra định dạng key.
- `OrderCreationFacade.create`: gom bước chiếm key, tạo đơn, ghi key.
- `OrderService.update / cancel`: chỉ khi đơn còn PENDING, bắt buộc header `If-Match` (khóa lạc quan theo phiên bản), sai phiên bản trả 412.
- `PackageService.create / replace / validate`: kiểm tra trọng lượng, kích thước, số ảnh theo `PackageProperties`; liên kết ảnh qua `FileService.attach`.

**Xem đơn**
- `OrderQueryService.list / get`: lọc theo trạng thái, ngày, trạm. Phạm vi dữ liệu theo vai trò (`OrderScopeResolver`, `OrderSpecs`): khách chỉ thấy đơn của mình, nhân viên trạm chỉ thấy đơn của trạm mình.
- `OrderTrackingService.get`: tiến trình giao hàng, vị trí drone, ETA, lịch sử trạng thái.

**Điều phối viên**
- `OrderReviewService.approve / reject`: duyệt hoặc từ chối (từ chối bắt buộc có lý do).
- `OrderScheduleService.schedule`: lên lịch cho đơn đã duyệt hoặc đơn giao thất bại (giao lại). `ScheduleSlotPolicy` kiểm tra khung giờ (thời gian dẫn tối thiểu, tối đa 7 ngày, slot 30 phút); `SlotCapacityService.lockAndCheck` khóa và kiểm tra chỗ trống tại cả trạm gửi và trạm nhận, hết chỗ trả `SLOT_FULL`.
- `AttemptReleaseService.releaseActive`: trả lại chỗ đã giữ khi lần giao thất bại.

**Nhân viên trạm**
- `OrderReceiptService.list / context / receive`: danh sách đơn chờ tiếp nhận, thông tin đối chiếu, xác nhận đã nhận kiện (đơn sang PREPARING).
- `OrderDeliveryProgressService.dispatch / arrive`: xuất phát (IN_TRANSIT) và đã tới trạm nhận (AWAITING_PICKUP).

**Máy trạng thái**
- `TransitionRules`: nguồn sự thật duy nhất về các cạnh chuyển trạng thái, mỗi cạnh có vai trò được phép và dữ liệu bắt buộc.
  Luồng chính: `PENDING → APPROVED → SCHEDULED → PREPARING → IN_TRANSIT → AWAITING_PICKUP → DELIVERED`. Nhánh phụ: `REJECTED`, `CANCELLED`, `FAILED` (từ 4 trạng thái đang xử lý), rồi `RETURNING → RETURNED`.
- `OrderTransitionService.transition`: cổng duy nhất đổi trạng thái: kiểm tra phiên bản, kiểm tra luật, đổi trạng thái, ghi `OrderStatusHistory`, phát sự kiện realtime. `recordCreation` ghi dòng lịch sử đầu tiên khi tạo đơn.

### 1.6 Thông báo và realtime (FR-08)

- `CustomerNotificationService.list / markRead / markAllRead` (`/v1/notifications`): thông báo trong ứng dụng của khách.
- `NotificationListener / NotificationDispatcher.dispatch`: khi đơn đổi trạng thái, tạo thông báo từ mẫu `NotificationTemplates`; `NotificationRecorder` lưu; `NotificationEmailSender` gửi email.
- `NotificationReconciler.reconcile` (chạy định kỳ 30 giây): xử lý bù các thông báo bị sót.
- WebSocket STOMP tại `/ws`: `StompSecurityInterceptor` kiểm tra JWT ở khung CONNECT; `OrderChannelAccess` và `StompReceiptInterceptor` giới hạn ai được nghe kênh nào; `OrderRealtimePublisher` và `NotificationRealtimePublisher` đẩy sự kiện.

### 1.7 Tệp và ảnh

`FileController` → `FileService` (lưu trữ MinIO qua `ObjectStorage`):
- `createUploadTicket`: cấp phiếu tải lên (presigned URL), kiểm tra loại ảnh (jpeg, png, webp), dung lượng tối đa 5 MiB và giới hạn số phiếu đang chờ.
- `completeUpload`: xác nhận đã tải, kiểm tra chữ ký tệp thật (`ImageSignature`).
- `createDownloadLink`: cấp link tải có thời hạn 5 phút, kiểm tra quyền qua `FileResourceAccessPolicy` và `OrderFileAccessPolicy`.
- `attach`: gắn tệp vào kiện hàng hoặc ảnh xác nhận giao.
- `FileCleanupService.cleanup` (chạy định kỳ 10 phút): dọn tệp tải lên dở dang hoặc không được gắn vào đâu.

### 1.8 API tích hợp và khóa API (NFR)

- `ApiKeyAdminController` → `ApiKeyService.issue / list / revoke / rotate` (`/v1/admin/api-keys`, SYSTEM_ADMIN): cấp, xem, thu hồi, đổi khóa. Khóa thô chỉ hiện đúng một lần lúc cấp.
- `ApiKeyAuthenticationFilter` + `ApiKeyAuthenticator`: xác thực khóa cho `/v1/integration/**` (chuỗi bảo mật riêng, không dùng JWT). So sánh hằng thời gian, giới hạn tần suất bằng `ApiKeyRateLimiter`.
- `IntegrationController` → `IntegrationQueryService.orderStatus / stations`: hai API chỉ đọc cho hệ thống ngoài, quyền theo `IntegrationScope` (`ORDERS_READ`, `STATIONS_READ`).

### 1.9 Hạ tầng chung

- `GlobalExceptionHandler`: đổi mọi lỗi thành JSON chuẩn có `code`, `message`, `traceId` và `errors` theo từng trường. `ApiException` và `ErrorCode` là mã lỗi nghiệp vụ.
- `RequestLoggingFilter`, `TraceContext`: ghi log và gắn mã truy vết cho mỗi request.
- `HealthController`: `GET /health` để kiểm tra hệ thống sống.
- `DataSeeder`: nạp 5 vai trò khi khởi động.
- Cấu hình: `SecurityConfig`, `JwtConfig`, `CorsConfig`, `StorageConfig`, `PackageConfig`, `ScheduleConfig`, `WebSocketConfig`, `OpenApiConfig` (Swagger tại `/swagger-ui.html`).

---

## 2. Web (`web/src`)

### 2.1 Khung ứng dụng

| File | Vai trò |
|---|---|
| `app/App.tsx`, `main.tsx` | Điểm vào, bọc `AuthProvider` và router. |
| `routes/AppRouter.tsx` | Khai báo toàn bộ route. Nhóm `RequireAuth` cần đăng nhập, nhóm `RequireRole` chỉ cho SYSTEM_ADMIN vào các trang quản trị. |
| `layouts/AuthLayout.tsx` | Bố cục hai cột của trang đăng nhập, đăng ký. |
| `layouts/MainLayout.tsx` | Header, sidebar theo vai trò, chuông thông báo, nút đăng xuất. Hàm tính mục menu đang sáng: chọn mục khớp đường dẫn dài nhất nên mỗi lúc chỉ một mục active. |
| `features/auth/RequireAuth.tsx`, `RequireRole.tsx` | Chuyển về `/login` nếu chưa đăng nhập, về trang chủ nếu sai vai trò. Đây chỉ là lớp tiện cho giao diện, quyền thật nằm ở backend. |

### 2.2 Thư viện `lib/` (gọi API và hàm dùng chung)

| File | Nội dung chính |
|---|---|
| `apiClient.ts` | Instance axios: tự gắn JWT; gặp 401 thì làm mới token đúng một lần (`refreshAccessTokenOnce` dùng chung một promise cho các request song song) rồi gọi lại; chuẩn hóa lỗi thành `ApiRequestError` (`status`, `code`, `traceId`, `errors`). |
| `authService.ts` | `register`, `login` (lưu cặp token, trả hồ sơ), `logout`. |
| `token.ts`, `session.ts` | `tokenStorage` và `userStorage`: đọc, ghi, xóa token và người dùng trong localStorage. |
| `adminService.ts` | `listAccounts`, `createAccount`, `updateAccount`, `setAccountEnabled`, `roles`, `updateRolePermissions`, `settings`, `updateSettings`. |
| `addressService.ts`, `profileService.ts`, `stationService.ts` | CRUD địa chỉ; xem, sửa hồ sơ; danh sách, tạo, sửa, đổi trạng thái và giám sát trạm. |
| `orderService.ts` | `list`, `get`, `review` (duyệt, từ chối), `update`, `cancel`, `create` (kèm Idempotency-Key). `ifMatchOf` lấy phiên bản để gửi header `If-Match`. |
| `orderForm.ts` | `validateOrderForm`, `buildOrderRequest`, `newIdempotencyKey`, `mapOrderServerErrors`: kiểm tra và dựng body đơn. |
| `packageService.ts` | `validatePackageForm`, `toPackageInput`, `fromPackageView`, `formatWeight`, `mapServerErrors`: biểu mẫu kiện hàng và giới hạn. |
| `scheduleService.ts` | `availability`, `schedule`; hàm giờ Việt Nam: `todayInVietnam`, `formatSlotTime`, `toMillis`. |
| `receiptService.ts`, `trackingService.ts` | Tiếp nhận kiện tại trạm; xem và đẩy tiến trình giao (`advance`), `mergeTimeline` gộp mốc thời gian. |
| `fileService.ts` | `uploadImage` (xin phiếu, tải lên, xác nhận) và `getDownloadUrl`. |
| `notificationService.ts`, `useNotifications.ts` | Gọi API thông báo; hook quản lý danh sách, số chưa đọc và nhận thông báo realtime. |
| `realtime.ts`, `useOrderRealtime.ts` | `RealtimeClient` (STOMP/WebSocket, tự kết nối lại, làm mới token); hook nhận sự kiện của đơn và tự tải lại khi mất đồng bộ. |
| `navigation.ts` | `NAV_ITEMS` (menu theo vai trò), `getMenuForRole`, `findNavItem`, `PLACEHOLDER_PATHS`. |
| `roles.ts`, `workflow.ts`, `deliveryStatus.ts` | Nhãn vai trò, các bước quy trình giao hàng, nhãn và màu trạng thái đơn. |
| `orderErrors.ts` | `explainOrderError`: đổi mã lỗi của backend thành câu tiếng Việt dễ hiểu. |
| `env.ts`, `demoAuth.ts`, `healthService.ts` | Biến môi trường (`VITE_API_URL`, `VITE_DEMO_AUTH`), người dùng giả cho chế độ demo, kiểm tra sức khỏe API. |

### 2.3 Trang và chức năng (`features/`)

| Nhóm | Trang | Mô tả |
|---|---|---|
| Xác thực | `LoginPage`, `RegisterPage` | Đăng nhập, đăng ký. Đăng ký kiểm tra từng ô, hiện lỗi dưới ô và lỗi chung từ server, xong thì chuyển về đăng nhập kèm thông báo. Cả hai dùng `PasswordInput`. |
| Trang chủ | `HomePage` | Banner chào, thẻ thống kê (khách, điều phối, hậu cần), lối tắt chức năng theo vai trò, sơ đồ quy trình giao hàng. Số liệu thống kê hiện chưa có API nên hiển thị "—". |
| Hồ sơ | `ProfilePage` | Xem và sửa thông tin cá nhân, đổi mật khẩu. |
| Địa chỉ | `AddressesPage`, `AddressPicker`, `CustomerGate`, `DeliveryAddressPreview` | Sổ địa chỉ của khách (thêm, sửa, xóa, phân trang); bộ chọn địa chỉ khi tạo đơn. |
| Đơn hàng | `CreateOrderPage`, `EditOrderPage`, `OrderForm` | Tạo và sửa đơn: chọn địa chỉ, trạm gửi/nhận, nhập kiện hàng, tải ảnh. |
| | `OrdersPage`, `OrderDetailPage` | Danh sách có lọc (trạng thái, ngày, trạm) và chi tiết đơn; chi tiết có lịch sử trạng thái, cập nhật realtime. |
| | `OrderCustomerActions` | Khách sửa hoặc hủy đơn khi còn chờ duyệt. |
| | `DispatchRequestsPage`, `OrderReviewActions`, `OrderScheduleActions` | Điều phối duyệt, từ chối, chọn khung giờ và lên lịch. |
| | `StationParcelsPage`, `StationReceiptPage` | Nhân viên trạm xem đơn chờ, đối chiếu và xác nhận nhận kiện. |
| | `OrderTrackingPanel` | Sơ đồ hai trạm và vị trí drone, ETA, mốc thời gian. |
| Kiện hàng | `PackageForm`, `PackageSummary`, `PackageFormPreviewPage` | Biểu mẫu kiện hàng dùng chung; trang xem thử chỉ có ở chế độ dev (`/dev/package-form`). |
| Trạm | `StationsPage`, `StationMonitoringPage` | Danh sách, tạo, sửa trạm; giám sát trạng thái, chỗ giữ, số kiện và đổi trạng thái theo vai trò. |
| Thông báo | `NotificationBell` | Chuông, số chưa đọc, danh sách, đánh dấu đã đọc, bấm vào thì mở đúng đơn. |
| Quản trị | `AccountsPage` | Bảng tài khoản, tìm kiếm có độ trễ, lọc, phân trang; modal thêm và sửa; hộp xác nhận khóa hoặc mở khóa; không cho tự khóa mình. |
| | `RolesPage` | Danh sách vai trò bên trái, checklist quyền theo nhóm bên phải; nhãn "Mặc định"; vai trò quản trị bị khóa; cảnh báo thay đổi chưa lưu. |
| | `SettingsPage` | Form cấu hình theo nhóm, kiểm tra kiểu và khoảng giá trị, nút Lưu và "Khôi phục mặc định". |

### 2.4 Thành phần giao diện dùng chung (`components/ui/`)

`PageHeader` (breadcrumb, tiêu đề, mã FR), `Brand` (logo), `PasswordInput` (ô mật khẩu có nút con mắt, `aria-label` đổi theo trạng thái), `StatusBadge` (nhãn trạng thái đơn), `ImageUploader` (chọn và tải ảnh), `SecureImage` (hiển thị ảnh qua link có thời hạn, hết hạn thì tự xin link mới), `PlaceholderPage` (trang tạm cho mục chưa làm).

---

## 3. Chức năng chưa hoàn thiện

- Các mục menu chưa có trang thật (hiển thị `PlaceholderPage`): lịch giao hàng, giao hàng thất bại, quản lý khách hàng, tình trạng sẵn sàng của trạm, thống kê và báo cáo, hiệu suất hoạt động, phân tích vận hành (AI), Audit Log, trợ lý AI (FR-10).
- Bảng quyền (`permissions`) chưa được nối vào kiểm tra truy cập nghiệp vụ.
- Bảng cấu hình hệ thống (`system_settings`) chưa được module nghiệp vụ nào đọc.
- Dữ liệu `audit_logs` đã được ghi nhưng chưa có API hay trang để xem.
- Số liệu thống kê trên trang chủ chưa có API cung cấp.
- Kết nối trạm hiện luôn trả `UNKNOWN` vì chưa có nguồn heartbeat.
