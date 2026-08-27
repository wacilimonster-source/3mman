package com.m3man.ui.about;

import java.io.File;
import java.util.List;

import com.m3man.ui.update.IBaseUpdate;

/**
 * @author flymegoc
 * @date 2017/12/23
 */

public interface IAbout extends IBaseUpdate {

    /**
     * 删除缓存文件
     *
     * @param fileDirList 缓存目录集
     */
    void cleanCacheFile(List<File> fileDirList);

    void countCacheFileSize(String title);

    void commonQuestions();
}
