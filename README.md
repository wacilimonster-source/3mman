# 3mman

本仓库仅用于 3mman App 的更新分发（检查更新 / 公告 / 常见问题），不包含项目源码介绍。

- 更新方式：App 内「关于 → 检查更新」会自动读取本仓库的 `version.txt`
- 发布方式：GitHub Actions 手动触发 `.github/workflows/release.yml`，自动构建、E7 签名、
  校验 SHA-1、创建 GitHub Release，并回写 `version.txt`
- 最新安装包：以 `version.txt` 的 `apkDownloadUrl` 为准（指向 GitHub Release 资产）
- 代码质量规则与历史教训见 [LESSONS.md](LESSONS.md)
