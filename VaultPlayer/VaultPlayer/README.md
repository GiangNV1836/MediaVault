# VaultPlayer: hướng dẫn dự án

## Trạng thái (đọc trước)
- Code viết đầy đủ theo `VaultPlayer_prompt.md` (GĐ1-GĐ7). **GĐ8 (backup kiểu journal) chưa làm** vì là mục tùy chọn.
- Đã chạy trình biên dịch Kotlin để kiểm tra cú pháp: **không có lỗi cú pháp**. Chưa biên dịch trọn vẹn vì môi trường của mình không có Android SDK, Compose, Room, LibVLC. Có thể còn vài lỗi biên dịch nhỏ (import thiếu, chữ ký API lệch phiên bản). Đó là việc của bước build.
- Chưa chạy thử trên máy thật.

## Hiệu năng đã xử lý (rà soát tĩnh, CHƯA đo trên máy thật)
- Quét/nhập/backup/ghi gói thumbnail chạy ở luồng nền ưu tiên thấp; tạm dừng khi đang phát video từ cùng nguồn.
- Danh sách phân trang (Paging 3); tìm theo tag dùng tập con tính một lần (không EXISTS cho từng dòng).
- Danh sách tag/playlist được throttle (tối đa 1 lần/0,4 giây) để không tính lại dồn dập khi nhập/quét hàng loạt.
- PBKDF2 (PIN, mật khẩu backup) chạy ở luồng nền; khóa Keystore được lưu đệm; LibVLC dùng chung một thể hiện; mở file và khởi tạo media player trên luồng nền.
- Player mặc định dùng Media3/Android MediaCodec; nếu phát sinh lỗi decoder thì thử LibVLC giải mã phần cứng tại vị trí hiện tại. Có thể chọn LibVLC phần cứng/phần mềm thủ công; không tự chuyển sang phần mềm theo timeout, dùng bộ đệm tệp 1000 ms.
- Player có timeline, chuyển mục, chạm đúp để tua ±10 giây và nhấn giữ để phát nhanh 2×; có thể phát lại từ đầu sau khi video kết thúc.
- Media phân trang ở tab Tất cả, Yêu thích và nội dung Playlist dùng chung hai kiểu hiển thị lưới cố định 2 cột/danh sách; có thể chuyển kiểu nhanh, bộ lọc cuộn ngang và không tự xuống dòng.
- Có tab Tag riêng để quản lý/lọc theo tag và tạo playlist lọc tag; tab Playlist riêng cho các danh sách phát. Nhấn giữ media để bật chọn nhiều, có checkbox và bộ đếm để gắn tag/yêu thích/tạo playlist.
- Chọn một playlist để xem các mục bên trong theo trang, phát tiếp từ mục đã chọn hoặc phát toàn bộ/ngẫu nhiên.
- Bộ chọn tag mở sẵn danh sách có thể tìm kiếm, tránh tạo trùng; quản lý tag dùng sheet gọn để thêm, tìm, đổi tên/gộp và xóa qua menu thao tác.
- Lần quét đầy đủ đầu tiên của nguồn mới sẽ tự nhập các mục tìm thấy sau khi quét thành công.
- UI Material 3 dùng màu hệ thống, tab dưới trên màn hẹp/NavigationRail trên màn rộng, lưới và thẻ co giãn; các dialog thao tác có nút quay lại và danh sách có trạng thái tải/lỗi.
- Gắn tag/yêu thích hàng loạt gói trong một transaction (một lần làm mới giao diện).
- Giới hạn đã biết: một thư mục đơn chứa hàng trăm nghìn tệp sẽ tốn RAM khi quét lần đầu; bản backup rất lớn (hàng chục MB) tạm tốn RAM gấp 2-3 lần kích thước khi mã hóa; mở danh sách rất lớn có độ trễ ngắn khi dựng hàng đợi phát.

## Mở và build
1. Android Studio (bản ổn định mới nhất) -> Open -> chọn thư mục `VaultPlayer`.
2. Gradle tự tải theo `gradle/wrapper/gradle-wrapper.properties` (không kèm gradle-wrapper.jar; Android Studio tự xử lý).
3. Phiên bản thư viện trong `gradle/libs.versions.toml` cố ý chọn bản cũ đã ổn định. Nếu sync báo lỗi, nâng phiên bản tương ứng.
4. Build -> Build APK(s). Bản release ký bằng khóa debug để cài trực tiếp (đổi khóa riêng nếu cần). Chỉ build ABI arm64-v8a.
5. Đánh giá độ mượt bằng bản **release**, không dùng bản debug.

## Cách dùng lần đầu
1. Mở app -> bắt buộc đặt PIN Admin (>= 4 số, không phải 1234).
2. Biểu tượng ổ khóa -> mở khóa -> Cài đặt -> "Thêm nguồn" -> chọn thư mục gốc của USB/thẻ nhớ.
3. App tự quét. Admin thấy "Có N mục mới" -> bấm để nhập. Sau đó gắn tag, tạo playlist.
4. Cài đặt -> đặt mật khẩu backup (backup tự động chỉ chạy sau khi đặt).

## Cấu trúc
```
app/src/main/java/com/vaultplayer/
  VaultApp.kt, Vault.kt, MainActivity.kt
  util/      Util.kt, Prefs.kt
  security/  Crypto.kt (Keystore, PBKDF2), Security.kt (PIN, phiên Admin)
  data/      Entities.kt, VaultDao.kt, AppDatabase.kt (SQLCipher), Repo.kt
  storage/   DocUtil.kt (SAF), Scanner.kt (quét/nhập), SourceManager.kt (cắm/rút, nguồn)
  thumb/     ThumbRepository.kt (RAM), PackStore.kt (gói mã hóa trên nguồn)
  backup/    BackupManager.kt
  player/    VlcPlayerWrapper.kt
  viewer/    ImageDecode.kt
  ui/        Theme, Common, Cards, Sheets, HomeScreen, SettingsScreen, PlayerHost, MainViewModel
```

## Khác biệt so với prompt (có chủ đích)
- Không dùng Navigation Compose và Coil: chuyển màn hình bằng state, giải mã ảnh bằng ImageDecoder (ít rủi ro API, kiểm soát bộ nhớ).
- MediaEntry có thêm cột `pathKey`, `dirKey` (so khớp không phân biệt hoa/thường) và DirState dùng `dirKey`.
- Ghép lại tệp đổi tên dùng (loại + dung lượng + ngày sửa), không đối chiếu 64KB đầu vì tệp cũ đã không còn để đọc.
- Admin xem đường dẫn thật ngay trong danh sách khi đã mở khóa (không có màn hình Quản lý riêng); màn hình phát/xem ảnh không bao giờ hiện.
- Wipe và "tạo mới khi database lỗi" kết thúc bằng việc đóng ứng dụng để khởi động sạch.
- DeviceInfo/ThumbIndex/PackInfo nằm trong Room; mật khẩu backup suy ra khóa lưu (bọc Keystore) để backup tự động.

## Giao cho AI Studio (dán kèm toàn bộ file)
```
Đây là dự án Android Kotlin + Jetpack Compose hoàn chỉnh (VaultPlayer). Chưa từng được biên dịch.
Nhiệm vụ:
1. Rà soát và sửa mọi lỗi biên dịch: import thiếu, chữ ký API sai (Compose Material3, Room 2.6.1 + SQLCipher, Paging 3, LibVLC), phiên bản thư viện.
2. KHÔNG đổi hành vi, KHÔNG bỏ chức năng, KHÔNG thêm quyền INTERNET, KHÔNG ghi log tên file/đường dẫn.
3. Giữ nguyên các quy tắc: User Mode không lộ tên file gốc/đường dẫn; thumbnail chỉ trong RAM và gói mã hóa trên nguồn ngoài; rút nguồn thì ẩn mọi dữ liệu của nguồn đó.
4. Trả về TỪNG FILE ĐÃ SỬA, đầy đủ nội dung, kèm đường dẫn; liệt kê ngắn mỗi thay đổi và lý do.
5. Cuối cùng hướng dẫn build APK debug/release.
```
