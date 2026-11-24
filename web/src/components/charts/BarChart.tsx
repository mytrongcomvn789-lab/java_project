import { useState } from "react";
import "./charts.css";

export interface BarChartDataPoint {
  label: string;
  value: number;
  color?: string;
  max?: number; // chỉ dùng cho thanh ngang: giá trị đầy thanh (vd sức chứa)
  text?: string; // chỉ dùng cho thanh ngang: chuỗi hiển thị thay cho giá trị
}

export interface StackedBarDataPoint {
  label: string;
  segments: { key: string; value: number; color: string; displayLabel: string }[];
}

interface BarChartProps {
  data: BarChartDataPoint[] | StackedBarDataPoint[];
  type: "vertical" | "horizontal" | "stacked";
  title: string;
  unit?: string;
  ariaLabel: string;
  height?: number;
  showTable?: boolean;
  formatValue?: (value: number) => string;
}

function isStacked(data: BarChartProps["data"]): data is StackedBarDataPoint[] {
  return data.length > 0 && "segments" in data[0];
}

export default function BarChart({
  data,
  type,
  title,
  unit = "",
  ariaLabel,
  height = 300,
  showTable = true,
  formatValue = (v) => v.toLocaleString("vi-VN"),
}: BarChartProps) {
  const [tooltip, setTooltip] = useState<{ x: number; y: number; content: string } | null>(null);
  const [tableVisible, setTableVisible] = useState(false);

  if (data.length === 0) {
    return (
      <div className="chart-container">
        <h3 className="chart-title">{title}</h3>
        <div className="chart-empty">Không có dữ liệu</div>
      </div>
    );
  }

  const handleMouseEnter = (e: React.MouseEvent, content: string) => {
    const rect = e.currentTarget.getBoundingClientRect();
    setTooltip({ x: rect.left + rect.width / 2, y: rect.top, content });
  };
  const handleMouseLeave = () => setTooltip(null);

  if (type === "horizontal" && !isStacked(data)) {
    const rows = data as BarChartDataPoint[];
    const globalMax = Math.max(1, ...rows.map((r) => r.max ?? r.value));
    const rowH = 34;
    const labelW = 150;
    const textW = 330;
    const trackW = 320;
    const w = labelW + trackW + textW;
    return (
      <div className="chart-container">
        <div className="chart-header">
          <h3 className="chart-title">{title}</h3>
          {showTable && (
            <button type="button" className="chart-toggle-table" onClick={() => setTableVisible(!tableVisible)}>
              {tableVisible ? "Ẩn bảng" : "Xem dạng bảng"}
            </button>
          )}
        </div>
        {!tableVisible ? (
          <div className="chart-svg-wrapper">
            <svg width={w} height={rows.length * rowH + 8} role="img" aria-label={ariaLabel} className="chart-svg">
              {rows.map((r, i) => {
                const full = r.max ?? globalMax;
                const trackLen = full > 0 ? (full / globalMax) * trackW : 0;
                const fill = full > 0 ? Math.min(1, r.value / full) * trackLen : 0;
                const y = i * rowH + 4;
                const text = r.text ?? `${formatValue(r.value)} ${unit}`.trim();
                return (
                  <g
                    key={r.label}
                    onMouseEnter={(e) => handleMouseEnter(e, `${r.label}\n${text}`)}
                    onMouseLeave={handleMouseLeave}
                  >
                    <text x={0} y={y + 16} fontSize={12} fill="var(--text)">
                      {r.label.length > 20 ? `${r.label.slice(0, 19)}…` : r.label}
                    </text>
                    <rect x={labelW} y={y + 4} width={trackLen} height={16} fill="var(--border)" rx={3} />
                    <rect x={labelW} y={y + 4} width={fill} height={16} fill={r.color || "var(--green)"} rx={3} />
                    <text x={labelW + trackLen + 8} y={y + 16} fontSize={12} fill="var(--text)">
                      {text}
                    </text>
                  </g>
                );
              })}
            </svg>
          </div>
        ) : (
          <div className="chart-table-wrapper">
            <table className="chart-table">
              <thead>
                <tr><th>Nhãn</th><th>Giá trị</th></tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.label}>
                    <td>{r.label}</td>
                    <td>{r.text ?? `${formatValue(r.value)} ${unit}`.trim()}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {tooltip && (
          <div
            className="chart-tooltip"
            style={{ position: "fixed", left: tooltip.x, top: tooltip.y - 10, transform: "translate(-50%, -100%)" }}
          >
            {tooltip.content.split("\n").map((line, i) => (<div key={i}>{line}</div>))}
          </div>
        )}
      </div>
    );
  }

  const margin = { top: 20, right: 20, bottom: 60, left: 60 };
  const chartWidth = type === "horizontal" ? 600 : Math.max(600, data.length * 60);
  const chartHeight = height;
  const innerWidth = chartWidth - margin.left - margin.right;
  const innerHeight = chartHeight - margin.top - margin.bottom;

  let maxValue = 0;
  if (isStacked(data)) {
    maxValue = Math.max(...data.map((d) => d.segments.reduce((sum, s) => sum + s.value, 0)));
  } else {
    maxValue = Math.max(...(data as BarChartDataPoint[]).map((d) => d.value));
  }
  // Mốc trục Y là số nguyên "đẹp" (1, 2, 5, 10, ...) vì đơn vị là số đơn/chỗ.
  const rawStep = Math.max(1, maxValue / 4);
  const pow = 10 ** Math.floor(Math.log10(rawStep));
  const frac = rawStep / pow;
  const step = Math.max(1, Math.ceil((frac <= 1 ? 1 : frac <= 2 ? 2 : frac <= 5 ? 5 : 10) * pow));
  const tickCount = Math.max(1, Math.ceil(maxValue / step));
  const yMax = step * tickCount;
  const yTickValues = Array.from({ length: tickCount + 1 }, (_, i) => i * step);

  const xScale = (index: number) => (innerWidth / data.length) * index + innerWidth / data.length / 2;
  const yScale = (value: number) => innerHeight - (value / yMax) * innerHeight;
  const barWidth = Math.min(50, innerWidth / data.length - 10);

  // Nhãn trục X: thưa khi nhiều ngày
  const xLabelStep = data.length > 30 ? Math.ceil(data.length / 15) : data.length > 15 ? 2 : 1;
  const rotateLabels = data.length > 15 || data.some((d) => d.label.length > 7);

  // Legend cho stacked
  const legendItems = isStacked(data) && data.length > 0
    ? Array.from(new Set(data[0].segments.map((s) => s.key))).map((key) => {
        const seg = data[0].segments.find((s) => s.key === key)!;
        return { key, color: seg.color, label: seg.displayLabel };
      })
    : [];

  return (
    <div className="chart-container">
      <div className="chart-header">
        <h3 className="chart-title">{title}</h3>
        {showTable && (
          <button
            type="button"
            className="chart-toggle-table"
            onClick={() => setTableVisible(!tableVisible)}
          >
            {tableVisible ? "Ẩn bảng" : "Xem dạng bảng"}
          </button>
        )}
      </div>

      {!tableVisible && (
        <>
          {legendItems.length > 0 && (
            <div className="chart-legend">
              {legendItems.map((item) => (
                <div key={item.key} className="chart-legend-item">
                  <span className="chart-legend-color" style={{ background: item.color }} />
                  {item.label}
                </div>
              ))}
            </div>
          )}

          <div className="chart-svg-wrapper">
            <svg
              width={chartWidth}
              height={chartHeight}
              role="img"
              aria-label={ariaLabel}
              className="chart-svg"
            >
              <g transform={`translate(${margin.left}, ${margin.top})`}>
                {/* Lưới ngang */}
                {yTickValues.map((tick) => (
                  <line
                    key={tick}
                    x1={0}
                    y1={yScale(tick)}
                    x2={innerWidth}
                    y2={yScale(tick)}
                    stroke="var(--border)"
                    strokeDasharray="2,2"
                  />
                ))}

                {/* Trục Y */}
                {yTickValues.map((tick) => (
                  <text
                    key={tick}
                    x={-10}
                    y={yScale(tick)}
                    textAnchor="end"
                    alignmentBaseline="middle"
                    fontSize={11}
                    fill="var(--muted)"
                  >
                    {formatValue(tick)}
                  </text>
                ))}

                {/* Cột stacked */}
                {type === "stacked" && isStacked(data) &&
                  data.map((point, i) => {
                    let cumulativeHeight = 0;
                    return (
                      <g key={point.label}>
                        {point.segments.map((seg) => {
                          const segHeight = (seg.value / yMax) * innerHeight;
                          const segY = innerHeight - cumulativeHeight - segHeight;
                          cumulativeHeight += segHeight;
                          return (
                            <rect
                              key={seg.key}
                              x={xScale(i) - barWidth / 2}
                              y={segY}
                              width={barWidth}
                              height={segHeight}
                              fill={seg.color}
                              onMouseEnter={(e) =>
                                handleMouseEnter(e, `${point.label}\n${seg.displayLabel}: ${formatValue(seg.value)}`)
                              }
                              onMouseLeave={handleMouseLeave}
                              style={{ cursor: "pointer" }}
                            />
                          );
                        })}
                      </g>
                    );
                  })}

                {/* Cột đơn */}
                {type === "vertical" && !isStacked(data) &&
                  (data as BarChartDataPoint[]).map((point, i) => (
                    <rect
                      key={point.label}
                      x={xScale(i) - barWidth / 2}
                      y={yScale(point.value)}
                      width={barWidth}
                      height={innerHeight - yScale(point.value)}
                      fill={point.color || "var(--green)"}
                      onMouseEnter={(e) => handleMouseEnter(e, `${point.label}\n${formatValue(point.value)} ${unit}`)}
                      onMouseLeave={handleMouseLeave}
                      style={{ cursor: "pointer" }}
                    />
                  ))}

                {/* Giá trị trên đầu cột (không chỉ phân biệt bằng màu) */}
                {type === "vertical" && !isStacked(data) &&
                  (data as BarChartDataPoint[]).map((point, i) => (
                    <text
                      key={`v-${point.label}`}
                      x={xScale(i)}
                      y={yScale(point.value) - 4}
                      textAnchor="middle"
                      fontSize={11}
                      fill="var(--text)"
                    >
                      {formatValue(point.value)}
                    </text>
                  ))}

                {/* Nhãn trục X */}
                {data.map((point, i) =>
                  i % xLabelStep === 0 ? (
                    <text
                      key={point.label}
                      x={xScale(i)}
                      y={innerHeight + 15}
                      textAnchor={rotateLabels ? "end" : "middle"}
                      fontSize={10}
                      fill="var(--muted)"
                      transform={rotateLabels ? `rotate(-35, ${xScale(i)}, ${innerHeight + 15})` : undefined}
                    >
                      {point.label}
                    </text>
                  ) : null
                )}

                {/* Đơn vị trục Y */}
                {unit && (
                  <text x={-margin.left + 8} y={-8} fontSize={11} fill="var(--muted)" fontWeight={600}>
                    {unit}
                  </text>
                )}
              </g>
            </svg>
          </div>
        </>
      )}

      {tableVisible && (
        <div className="chart-table-wrapper">
          <table className="chart-table">
            <thead>
              <tr>
                <th>Nhãn</th>
                {isStacked(data) ? (
                  legendItems.map((item) => <th key={item.key}>{item.label}</th>)
                ) : (
                  <th>Giá trị</th>
                )}
              </tr>
            </thead>
            <tbody>
              {isStacked(data)
                ? data.map((point) => (
                    <tr key={point.label}>
                      <td>{point.label}</td>
                      {point.segments.map((seg) => (
                        <td key={seg.key}>{formatValue(seg.value)}</td>
                      ))}
                    </tr>
                  ))
                : (data as BarChartDataPoint[]).map((point) => (
                    <tr key={point.label}>
                      <td>{point.label}</td>
                      <td>{formatValue(point.value)} {unit}</td>
                    </tr>
                  ))}
            </tbody>
          </table>
        </div>
      )}

      {tooltip && (
        <div
          className="chart-tooltip"
          style={{
            position: "fixed",
            left: tooltip.x,
            top: tooltip.y - 10,
            transform: "translate(-50%, -100%)",
          }}
        >
          {tooltip.content.split("\n").map((line, i) => (
            <div key={i}>{line}</div>
          ))}
        </div>
      )}
    </div>
  );
}
