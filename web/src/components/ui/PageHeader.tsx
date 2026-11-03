interface Props {
  title: string;
  subtitle?: string;
  code?: string; // mã yêu cầu SRS hiển thị ở góc phải tiêu đề
  crumbs?: string[]; // đường dẫn; mục cuối được in đậm
}

// Đầu trang chung: breadcrumb + tiêu đề + mã yêu cầu (theo thiết kế mẫu).
export default function PageHeader({ title, subtitle, code, crumbs }: Props) {
  const trail = crumbs ?? [title];
  return (
    <>
      <div className="breadcrumb">
        {trail.map((c, i) => (
          <span key={c + i}>
            {i > 0 && <span style={{ marginRight: 10 }}>›</span>}
            {i === trail.length - 1 ? <strong>{c}</strong> : c}
          </span>
        ))}
      </div>
      <div className="page-title-row">
        <div>
          <h2>{title}</h2>
          {subtitle && <p className="muted">{subtitle}</p>}
        </div>
        {code && <span className="page-code">{code}</span>}
      </div>
    </>
  );
}
