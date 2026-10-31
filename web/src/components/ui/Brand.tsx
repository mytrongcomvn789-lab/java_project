export default function Brand({ large = false }: { large?: boolean }) {
  return (
    <div className={`brand ${large ? "large" : ""}`}>
      <span className="brand-icon">⬡</span>
      <div>
        <div className="brand-name">TRẠM GIAO</div>
        <div className="brand-sub">SMART DRONE DELIVERY</div>
      </div>
    </div>
  );
}
