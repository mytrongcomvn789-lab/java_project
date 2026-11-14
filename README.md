# SmartDroneDelivery

Nền tảng quản lý giao hàng bằng drone.

Stack: Spring Boot (Java 21) | SQL Server | React (Vite) | Flutter | Docker

## Chạy nhanh

```powershell
Copy-Item .env.example .env
docker compose up -d
Set-Location backend; mvn spring-boot:run
Set-Location ..\web; npm install; npm run dev
Set-Location ..\mobile; flutter pub get; flutter run
```

`docker compose up -d` starts SQL Server, creates the `smartdrone` database, and starts MinIO.
Replace the sample values in `.env` before using them.

Chạy backend không cần SQL Server (dữ liệu thử trong RAM):
`mvn spring-boot:run "-Dspring-boot.run.profiles=h2"` tại thư mục `backend`.

Hướng dẫn mục 3.1: [Hồ sơ và địa chỉ](docs/05-api/addresses-api.md).
Hướng dẫn mục 3.2: [API trạm hạ cánh](docs/05-api/stations-api.md).
Frontend: `http://localhost:5173`; Swagger: `http://localhost:5000/api/swagger-ui/index.html`.
