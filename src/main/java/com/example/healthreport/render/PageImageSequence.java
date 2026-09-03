package com.example.healthreport.render;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 任务的全局页面图序列：全部文件按 fileIndex 顺序、文件内按页序拼成一个序列。
 *
 * <p>不可变；构造时同时建立 page → (fileIndex, pageInFile) 映射表，之后只读。
 * {@link #locate} 是查数组，不是推断——模型只报全局 page，
 * 「它属于哪个文件的第几页」由这张表回答（设计方案 §0-2）。</p>
 */
public final class PageImageSequence {

    private final List<PageImage> pageList;
    private final int[] fileIndexByPage;
    private final int[] pageInFileByPage;

    private PageImageSequence(List<PageImage> pageList, int[] fileIndexByPage, int[] pageInFileByPage) {
        this.pageList = Collections.unmodifiableList(pageList);
        this.fileIndexByPage = fileIndexByPage;
        this.pageInFileByPage = pageInFileByPage;
    }

    /** 全局页数。 */
    public int size() {
        return pageList.size();
    }

    /** 按全局页码定位所属文件；越界页引用是契约错误。 */
    public FileLocation locate(int page) {
        assertPageInRange(page);
        return new FileLocation(fileIndexByPage[page - 1], pageInFileByPage[page - 1]);
    }

    /** 全局顺序的只读页列表。 */
    public List<PageImage> getPageList() {
        return pageList;
    }

    private void assertPageInRange(int page) {
        if (page < 1 || page > pageList.size()) {
            throw new IllegalArgumentException("页码越界：page=" + page + "，总页数=" + pageList.size());
        }
    }

    /** 由转图服务按文件顺序累加构建；构建完成后序列不可再变。 */
    public static final class Builder {

        private final List<PageImage> pageList = new ArrayList<PageImage>();
        private final List<Integer> fileIndexList = new ArrayList<Integer>();
        private final List<Integer> pageInFileList = new ArrayList<Integer>();
        private int lastFileIndex = -1;

        /** 追加一个文件的一页；文件必须按 fileIndex 非降序进入，文件内页码由本方法分配核对。 */
        public Builder addPage(int fileIndex, int pageInFile, byte[] jpegBytes) {
            if (fileIndex < lastFileIndex) {
                throw new IllegalStateException("文件顺序必须按 fileIndex 非降序");
            }
            lastFileIndex = fileIndex;
            int globalPage = pageList.size() + 1;
            pageList.add(new PageImage(globalPage, jpegBytes));
            fileIndexList.add(Integer.valueOf(fileIndex));
            pageInFileList.add(Integer.valueOf(pageInFile));
            return this;
        }

        public PageImageSequence build() {
            if (pageList.isEmpty()) {
                throw new IllegalStateException("图序列不能为空");
            }
            int[] fileIndexByPage = new int[pageList.size()];
            int[] pageInFileByPage = new int[pageList.size()];
            for (int index = 0; index < pageList.size(); index++) {
                fileIndexByPage[index] = fileIndexList.get(index).intValue();
                pageInFileByPage[index] = pageInFileList.get(index).intValue();
            }
            return new PageImageSequence(new ArrayList<PageImage>(pageList),
                    fileIndexByPage, pageInFileByPage);
        }
    }
}
