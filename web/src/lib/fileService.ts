import axios from "axios";
import apiClient from "./apiClient";

export type FilePurpose = "PACKAGE_PHOTO" | "CONFIRMATION_PHOTO";

export interface UploadTicket {
  fileId: string;
  upload: {
    url: string;
    method: "POST";
    fileFieldName: string;
    fields: Record<string, string>;
  };
  expiresAt: string;
  maxSizeBytes: number;
}

export interface FileView {
  id: string;
  purpose: FilePurpose;
  status:
    | "PENDING_UPLOAD"
    | "READY"
    | "ATTACHED"
    | "REJECTED"
    | "EXPIRED"
    | "DELETED";
  contentType: string;
  sizeBytes: number | null;
  createdAt: string;
}

export const ALLOWED_IMAGE_TYPES = ["image/jpeg", "image/png", "image/webp"];

// Xin ticket → POST thẳng lên MinIO → xác nhận. Trả về fileId để gắn vào đơn/ảnh xác nhận.
export async function uploadImage(
  file: File,
  purpose: FilePurpose,
): Promise<FileView> {
  const { data: ticket } = await apiClient.post<UploadTicket>(
    "/v1/files/upload-tickets",
    {
      purpose,
      contentType: file.type,
      sizeBytes: file.size,
    },
  );

  const form = new FormData();
  Object.entries(ticket.upload.fields).forEach(([k, v]) => form.append(k, v));
  form.append(ticket.upload.fileFieldName, file); // "file" phải là field CUỐI CÙNG

  // Dùng axios gốc: không gắn JWT của hệ thống vào request gửi sang MinIO.
  await axios.post(ticket.upload.url, form, { timeout: 60000 });

  const { data } = await apiClient.post<FileView>(
    `/v1/files/${ticket.fileId}/complete`,
  );
  return data;
}

export async function getDownloadUrl(fileId: string): Promise<string> {
  const { data } = await apiClient.get<{ url: string }>(
    `/v1/files/${fileId}/download-link`,
  );
  return data.url;
}
