# SmartDroneDelivery

Nền tảng quản lý giao hàng bằng drone.

Stack: ASP.NET Core 8 | PostgreSQL | React (Vite) | Flutter | Docker

## Chạy nhanh

```bash
docker compose up -d                      # PostgreSQL
cd backend/src/SmartDrone.Api && dotnet run
cd web && npm install && npm run dev
cd mobile && flutter pub get && flutter run
```
