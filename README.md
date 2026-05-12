# BleRepeaterLab

Dự án prototype ứng dụng Android dùng để quét thiết bị BLE, capture profile GATT và giả lập (emulate) lại thiết bị đó.

## Yêu cầu môi trường
- **JDK 17**: Cần thiết để build project.
- **Android SDK**: Cần command line tools và platform-tools (adb).
- **Thiết bị Android thật**: Khuyến khích dùng để test tính năng BLE (Emulator trên máy tính thường không hỗ trợ đầy đủ BLE peripheral).

## Hướng dẫn cài đặt và build (qua Terminal)
1. Cấp quyền thực thi cho Gradle wrapper:
   ```bash
   chmod +x gradlew
   ```
2. Build ứng dụng (Debug APK):
   ```bash
   ./gradlew assembleDebug
   ```
3. Cài đặt lên thiết bị qua ADB:
   ```bash
   ./gradlew installDebug
   ```
4. Chạy ứng dụng:
   ```bash
   adb shell monkey -p com.duongtc.blerepeaterlab 1
   ```

## Cách sử dụng và Test
1. Mở app trên điện thoại Android A.
2. Tại tab **Quét**, bấm **Start Scan** để tìm thiết bị BLE thật.
3. Bấm vào thiết bị muốn clone trong danh sách. App sẽ tự động dừng quét, kết nối và đọc toàn bộ GATT profile.
4. Chuyển sang tab **Chi tiết** để xem thông tin đã capture được (Services, Characteristics, Values).
5. Bấm **Start Emulator** để bắt đầu giả lập thiết bị này.
6. Dùng điện thoại Android B (hoặc app nRF Connect) để quét và kết nối tới thiết bị giả lập này để kiểm tra.

## Giới hạn hiện tại (Caveats)
- **MAC Address**: Android không cho phép thay đổi địa chỉ MAC Bluetooth của máy thành MAC của thiết bị khác. Do đó thiết bị giả lập sẽ mang MAC của điện thoại A.
- **Advertising Packet**: Có giới hạn kích thước (thường là 31 bytes cho legacy advertising). Nếu dữ liệu clone quá lớn, app sẽ ưu tiên advertise Service UUID chính.
- **Proxy Real-time**: Phiên bản prototype này chỉ trả về dữ liệu đã cache được từ lúc kết nối thiết bị thật, chưa hỗ trợ bridge/proxy lệnh đọc/ghi real-time giữa client giả lập và thiết bị thật.
- **GATT Operation Queue**: Các thao tác đọc dữ liệu được thực hiện tuần tự để tránh lỗi "GATT busy" kinh điển của Android.

## Tích hợp VSCode
Dự án đã được cấu hình sẵn các task trong `.vscode/tasks.json`. Bạn có thể mở Command Palette (`Ctrl+Shift+P`), gõ `Run Task` và chọn các tác vụ như:
- `Gradle: Clean`
- `Gradle: Build Debug`
- `Gradle: Install Debug`
- `ADB: Launch App`
- `ADB: Logcat App` (Lọc log theo tag BleRepeaterLab)
