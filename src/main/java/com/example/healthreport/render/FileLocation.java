package com.example.healthreport.render;

/** 全局页码到（文件序号，文件内页码）的定位结果。 */
public final class FileLocation {

    private final int fileIndex;
    private final int pageInFile;

    public FileLocation(int fileIndex, int pageInFile) {
        if (fileIndex < 0 || pageInFile < 1) {
            throw new IllegalArgumentException("文件定位参数无效");
        }
        this.fileIndex = fileIndex;
        this.pageInFile = pageInFile;
    }

    /** 文件在任务内的顺序，从 0 起。 */
    public int getFileIndex() {
        return fileIndex;
    }

    /** 文件内页码，从 1 起。 */
    public int getPageInFile() {
        return pageInFile;
    }
}
