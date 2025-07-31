## Giới thiệu

Ứng dụng gọi video call cho Web app và Android app sử dụng công nghệ WebRTC.

## Công nghệ sử dụng 

- **WebRTC:** Công nghệ mã nguồn mở cho phép giao tiếp real-time trực tiếp giữa các peer (trình duyệt, thiết bị di động...)
- **NodeJs:** Tạo Signaling Server
- **Socket.IO:** Thư viện hỗ trợ giao tiếp giữa các peer thông qua Signaling Server.
- **Jetpack Compose:** Tạo UI cho Android app.
- **Kotlin Coroutine:** Lập trình bất đồng bộ.

## Cách hoạt động của WebRTC

Hai peer thực hiện các bước sau để có thể gửi và nhận data real-time:

- Trao đổi thông tin cấu hình (SDP) bằng cách gửi Offer/Answer cho nhau qua Signaling Server.

- Trao đổi thông tin mạng (ICE Candidates) qua Signaling Server.

- Sau khi kết nối thành công, 2 peer có thể gửi real-time data trực tiếp cho nhau.

### Các thành phần của WebRTC

- **Peer:** Là các bên giao tiếp với nhau như: trình duyệt, thiết bị di động...
- **Signaling Server:** Đối tượng trung gian điều phối SDP, ICE Candidates đến các peer.
- **STUN Server:** Các peer gửi request đến STUN Server để lấy thông tin về `Public IP Address:Port` (ICE Candidates)
- **TURN Server:** Chuyển tiếp data giữa các peer mà không thể kết nối trực tiếp do NAT/firewall.
