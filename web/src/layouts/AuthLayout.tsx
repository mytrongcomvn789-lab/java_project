import { Outlet } from "react-router-dom";
import Brand from "../components/ui/Brand";

export default function AuthLayout() {
  return (
    <div className="auth-split">
      <section className="auth-hero">
        <Brand large />
        <div>
          <p className="eyebrow">KẾT NỐI TỪ TRẠM ĐẾN TRẠM</p>
          <h1>
            Gửi một kiện hàng.
            <br />
            Kết nối một hành trình.
          </h1>
          <p className="muted">
            Từ yêu cầu giao hàng đến khoảnh khắc nhận kiện. Mỗi bước đều rõ
            ràng, mỗi hành trình đều được theo dõi.
          </p>
        </div>
        <p className="eyebrow">ĐỒ ÁN SMARTDRONEDELIVERY · BẢN THỬ THEO FIGMA</p>
      </section>
      <section className="auth-panel">
        <Outlet />
      </section>
    </div>
  );
}
