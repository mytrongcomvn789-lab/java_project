import { useRef, useState } from "react";
import {
  ALLOWED_IMAGE_TYPES,
  uploadImage,
  type FilePurpose,
} from "../../lib/fileService";
import { ApiRequestError } from "../../lib/apiClient";
import SecureImage from "./SecureImage";

interface Props {
  purpose: FilePurpose;
  maxSizeBytes?: number; // chỉ để báo lỗi sớm; server vẫn là nơi quyết định
  onUploaded: (fileId: string) => void; // gắn fileId vào form đơn hàng / ảnh xác nhận
}

export default function ImageUploader({
  purpose,
  maxSizeBytes = 5 * 1024 * 1024,
  onUploaded,
}: Props) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [fileId, setFileId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function onPick(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    setError(null);
    if (!ALLOWED_IMAGE_TYPES.includes(file.type))
      return setError("Chỉ nhận ảnh JPEG, PNG hoặc WebP");
    if (file.size > maxSizeBytes)
      return setError(
        `Ảnh vượt quá ${Math.round(maxSizeBytes / 1024 / 1024)} MiB`,
      );

    setBusy(true);
    try {
      const result = await uploadImage(file, purpose);
      setFileId(result.id);
      onUploaded(result.id);
    } catch (err) {
      setError(
        err instanceof ApiRequestError
          ? err.message
          : "Tải ảnh thất bại, vui lòng thử lại",
      );
    } finally {
      setBusy(false);
      if (inputRef.current) inputRef.current.value = "";
    }
  }

  return (
    <div>
      <input
        ref={inputRef}
        type="file"
        accept={ALLOWED_IMAGE_TYPES.join(",")}
        onChange={onPick}
        disabled={busy}
      />
      {busy && <p>Đang tải lên…</p>}
      {error && <p role="alert">{error}</p>}
      {fileId && <SecureImage fileId={fileId} alt="Ảnh đã tải lên" />}
    </div>
  );
}
