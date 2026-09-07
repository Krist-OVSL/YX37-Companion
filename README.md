# YX37 Companion (Android) - v1.1.0

Ứng dụng Android điều khiển phần cứng và tinh chỉnh Equalizer (EQ) trực tiếp cho tai nghe **YX37** qua kết nối Bluetooth.

---

## 🌟 Tính năng nổi bật

- **Điều khiển Hardware EQ trực tiếp**: Giao tiếp trực tiếp với chip DSP của tai nghe qua giao thức JieLi RCSP (Bluetooth RFCOMM / SPP), áp dụng âm sắc tức thì vào phần cứng tai nghe.
- **Dynamic Hardware Bands**: Tự động truy vấn và đồng bộ danh sách dải tần (frequencies) thực tế do phần cứng tai nghe hỗ trợ.
- **Preset âm thanh đa dạng**:
  - **Flat**: Cân bằng nguyên bản.
  - **Bass Boost**: Tăng cường dải trầm mạnh mẽ.
  - **Treble Boost**: Tăng độ chi tiết và sáng của dải cao.
  - **Vocal**: Tôn giọng hát và đàm thoại rõ ràng.
  - **Gaming**: Tối ưu âm thanh không gian và tiếng bước chân.
- **Quản lý đa cấu hình tùy chỉnh (Custom Profiles)**: Tạo mới không giới hạn nhiều cấu hình tùy chỉnh cá nhân, đổi tên, và xóa dễ dàng ngay trên giao diện.
- **Tự động lưu & Khôi phục**: Lưu lại trạng thái tùy chỉnh của bạn và tự động nạp lại mỗi khi tai nghe kết nối.
- **Giao diện Material 3 hiện đại**: Xây dựng hoàn toàn bằng Jetpack Compose, hiển thị trực quan trạng thái kết nối Bluetooth.

---

## 📲 Cài đặt

1. Tải file **`YX37_Companion.apk`** từ mục **[Releases](https://github.com/Krist-OVSL/YX37-Companion/releases)** của repository này.
2. Cài đặt file APK trên điện thoại Android của bạn (hỗ trợ Android 8.0 trở lên).
3. Mở ứng dụng, cấp quyền **Bluetooth** (Thiết bị ở gần) khi được yêu cầu.
4. Bật tai nghe YX37 và kết nối Bluetooth, ứng dụng sẽ tự động nhận diện và sẵn sàng điều chỉnh EQ!

---

## 🛠️ Build từ mã nguồn

### Yêu cầu:
- **Android Studio** Ladybug hoặc mới hơn (hoặc IntelliJ IDEA).
- **JDK 17** hoặc **JDK 21**.
- **Android SDK** API 35 (Build-tools 35.0.0).

### Lệnh biên dịch:
```bash
# Clone repository
git clone https://github.com/Krist-OVSL/YX37-Companion.git
cd YX37-Companion

# Build Debug APK
./gradlew assembleDebug

# Build Release APK
./gradlew assembleRelease
```

File APK đầu ra sẽ nằm tại:
- `app/build/outputs/apk/release/app-release.apk`
- `app/build/outputs/apk/debug/app-debug.apk`

---

## 📄 Bản quyền (License)

Dự án được phân phối dưới giấy phép [MIT License](LICENSE).
