import { useState } from "react";
import type { InputHTMLAttributes } from "react";
import { Eye, EyeOff } from "lucide-react";

type Props = Omit<InputHTMLAttributes<HTMLInputElement>, "type">;

// Ô mật khẩu có nút con mắt hiện/ẩn, dùng chung cho trang Đăng nhập và Đăng ký.
export default function PasswordInput(props: Props) {
  const [visible, setVisible] = useState(false);
  const Icon = visible ? EyeOff : Eye;
  return (
    <div className="password-input">
      <input {...props} type={visible ? "text" : "password"} />
      <button
        type="button"
        className="password-toggle"
        aria-label={visible ? "Ẩn mật khẩu" : "Hiện mật khẩu"}
        aria-pressed={visible}
        onClick={() => setVisible((v) => !v)}
      >
        <Icon size={19} strokeWidth={1.8} />
      </button>
    </div>
  );
}
