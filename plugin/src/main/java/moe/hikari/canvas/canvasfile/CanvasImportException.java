package moe.hikari.canvas.canvasfile;

/** 导入失败，带稳定错误码（IMPORT_*，见 docs/import-export.md §4）。 */
public class CanvasImportException extends Exception {
    private final String code;

    public CanvasImportException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
