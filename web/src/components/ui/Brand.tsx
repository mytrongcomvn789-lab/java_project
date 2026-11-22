export default function Brand({ large = false }: { large?: boolean }) {
  return (
    <div className={`brand ${large ? "large" : ""}`}>
      <img className="brand-icon" src="/images/logo.svg" alt="" />
      <div>
        <div className="brand-name">TRẠM GIAO</div>
        <div className="brand-sub">SMART DRONE DELIVERY</div>
      </div>
    </div>
  );
}
