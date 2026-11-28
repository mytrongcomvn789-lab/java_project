/** Quy đổi số phút sang dạng dễ đọc: dưới 60 phút giữ phút; từ 60 phút là giờ; từ 24 giờ là ngày. */
export function formatMinutes(totalMinutes: number): string {
  const min = Math.max(0, Math.round(totalMinutes));
  if (min < 60) return `${min} phút`;
  const hours = Math.floor(min / 60);
  const m = min % 60;
  if (hours < 24) return m === 0 ? `${hours} giờ` : `${hours} giờ ${m} phút`;
  const days = Math.floor(hours / 24);
  const h = hours % 24;
  return h === 0 ? `${days} ngày` : `${days} ngày ${h} giờ`;
}
