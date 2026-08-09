package com.qimeng.media.backup

object BackupFileNames {
    /**
     * 备份 v2.0 唯一数据文件：单文件全量 JSON。
     * 写入备份目录根目录（绮梦影库/），不再拆分子目录/分文件。
     * 字段自解释，AI 可直接读取生成报告（无需 App 内置报告器）。
     */
    const val QIMENG_BACKUP = "qimeng_backup.json"
}
