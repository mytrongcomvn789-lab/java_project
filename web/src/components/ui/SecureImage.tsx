import { useCallback, useEffect, useState } from "react";
import { getDownloadUrl } from "../../lib/fileService";

interface Props {
  fileId: string;
  alt: string;
  className?: string;
}

// Không lưu signed URL lâu dài: chỉ giữ trong state; <img> lỗi (link hết hạn) thì xin link mới 1 lần.
export default function SecureImage({ fileId, alt, className }: Props) {
  const [url, setUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [retried, setRetried] = useState(false);

  const load = useCallback(async () => {
    try {
      setUrl(await getDownloadUrl(fileId));
      setFailed(false);
    } catch {
      setFailed(true);
    }
  }, [fileId]);

  useEffect(() => {
    setRetried(false);
    void load();
  }, [load]);

  const onError = () => {
    if (!retried) {
      setRetried(true);
      void load();
    } else {
      setFailed(true);
    }
  };

  if (failed) {
    return (
      <button
        type="button"
        onClick={() => {
          setRetried(false);
          void load();
        }}
      >
        Không tải được ảnh. Bấm để thử lại
      </button>
    );
  }
  return url ? (
    <img src={url} alt={alt} className={className} onError={onError} />
  ) : (
    <span>Đang tải ảnh…</span>
  );
}
