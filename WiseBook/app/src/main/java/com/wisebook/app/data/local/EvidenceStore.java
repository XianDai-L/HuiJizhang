package com.wisebook.app.data.local;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 证据文件（截图 / 录音）的落盘与清理。
 *
 * <p>用户明确要求保留原图——<b>要让用户明白这笔账单出自哪里</b>（HANDOFF 决策 47），
 * 所以这里不做压缩、不做容量淘汰：一张图几百 KB，而它是这笔账唯一的来源凭据。
 *
 * <p><b>只存相对路径</b>（形如 {@code evidence/IMG-1790000000000.jpg}）：
 * 应用私有目录的绝对路径在不同安装、迁移或被系统搬迁后可能变，
 * 存相对路径、每次用 {@code filesDir} 拼回去才是稳的。
 *
 * <p>文件放在 {@code filesDir} 下：应用私有、不进系统相册、其它 App 读不到。
 * 用户的意思是"既然上传了就不算隐私"，但"不额外扩大暴露面"仍然是应该守的底线。
 */
public final class EvidenceStore {

    /** 相对路径的前缀，同时也是 filesDir 下的子目录名 */
    public static final String DIR = "evidence";

    private final File baseDir;

    public EvidenceStore(File filesDir) {
        this.baseDir = new File(filesDir, DIR);
    }

    /**
     * 保存一张图。
     *
     * @param name 不含扩展名的文件名（用批次标签即可，让文件与那一次上传对得上）
     * @return 相对路径，存进 {@code t_draft.evidence_ref}；写不进去时返回 {@code null}
     */
    public String saveImage(byte[] bytes, String mimeType, String name) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            if (!baseDir.isDirectory() && !baseDir.mkdirs()) {
                return null;
            }
            File target = new File(baseDir, name + extensionOf(mimeType));
            try (FileOutputStream out = new FileOutputStream(target)) {
                out.write(bytes);
            }
            return DIR + "/" + target.getName();
        } catch (IOException e) {
            // 落盘失败就当"这次没有证据"：宁可少一个可解释性附件，
            // 也不能让一笔已经解析成功的账记不下来
            return null;
        }
    }

    /**
     * 相对路径 → 磁盘文件。
     *
     * @return 文件不存在（被清理过、或从未写成功）时返回 {@code null}，
     *         调用方据此决定"不显示原图"，而不是拿到一个空路径
     */
    public File resolve(String relativePath) {
        if (relativePath == null || relativePath.trim().isEmpty()) {
            return null;
        }
        File file = new File(baseDir.getParentFile(), relativePath.trim());
        return file.isFile() ? file : null;
    }

    /** 删除一个证据文件；文件本来就不在时视作成功 */
    public void delete(String relativePath) {
        File file = resolve(relativePath);
        if (file != null) {
            // 删不掉不抛异常：这时账目已经撤销了，为一个孤儿文件让整次操作报错不值得
            file.delete();
        }
    }

    private static String extensionOf(String mimeType) {
        if (mimeType == null) {
            return ".jpg";
        }
        String lower = mimeType.toLowerCase();
        if (lower.contains("png")) {
            return ".png";
        }
        if (lower.contains("webp")) {
            return ".webp";
        }
        return ".jpg";
    }
}
