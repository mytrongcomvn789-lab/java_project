# SmartDroneDelivery

Nền tảng quản lý giao hàng bằng drone.

Stack: Spring Boot (Java 21) | PostgreSQL | React (Vite) | Flutter | Docker

## Chạy nhanh

```bash
docker compose up -d                      # PostgreSQL
cd backend && mvn spring-boot:run
cd web && npm install && npm run dev
cd mobile && flutter pub get && flutter run
```

Chạy backend không cần PostgreSQL (dữ liệu thử trong RAM):
`mvn spring-boot:run "-Dspring-boot.run.profiles=h2"` tại thư mục `backend`.

Hướng dẫn mục 3.1: [Hồ sơ và địa chỉ](docs/05-api/addresses-api.md).
Frontend: `http://localhost:5173`; Swagger: `http://localhost:5000/api/swagger-ui/index.html`.
