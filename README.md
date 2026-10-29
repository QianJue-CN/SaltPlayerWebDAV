# SaltPlayerWebDAV

Salt Player 的 WebDAV / AList 音乐库模组，作者 **QianJue**。目前适配 **Windows 版 Salt Player 1.19.3**。

## 功能

- WebDAV、AList / OpenList 音乐浏览与在线播放。
- 音乐文件双向增量同步，冲突保留、远端旧版本备份。
- 读取标题、艺术家、专辑等标签，支持远端同名 LRC 和内嵌歌词。
- 设置嵌入播放器配置页，跟随深浅主题，不打开独立窗口。

## 使用

1. 按[官方说明](https://saltplayer.com/workshop/usage.html#本地导入)导入 `.spmod`，启用并授予“方法与 UI Hook”权限。
2. 进入模组配置，填写服务器地址、远端音乐目录及账号，点击“连接并扫描”。
3. 选择歌曲在线播放，或填写本地音乐目录后点击“双向同步”。

**AList 示例：**选择“AList / OpenList”，地址填 `https://alist.example.com`，远端目录填 `/音乐`，自动使用 `/dav/音乐/`。也支持直接填写带反向代理前缀的 WebDAV 入口。

服务端需启用 WebDAV；同步账号需有读写、创建目录和移动文件权限；在线播放需支持 HTTP Range。密码仅保存在内存，停用后需重新输入。

歌词优先读取同目录的同名 `.lrc`／`.LRC`（如 `歌曲.flac` 对应 `歌曲.lrc`），没有时使用内嵌歌词。支持 UTF-8、UTF-16及运行时支持的 GB18030。标签读取支持 MP3、FLAC、M4A / MP4、OGG Vorbis、Opus。

## 同步与限制

- 单端新增文件会补到另一端；有同步基线时，单端修改会同步到另一端。
- 首次同步遇到同名不同内容，或两端同时修改时，保留两端文件并报告冲突。
- **不传播删除**：单端删除后，下次同步会从另一端补回。
- 更新远端文件前，旧版本保存在 `.saltplayer-webdav-backups/`；确认无误后可自行清理。
- 仅同步音频文件及内嵌标签，不同步外置歌词、收藏、歌单或播放器数据库。
- 本地同步目录需手动加入播放器音乐库并扫描；暂不支持网络封面展示。
- 单首标签最多读取 8 MB，外置歌词最多读取 1 MB；读取失败不阻断播放。

更新后重新启用并从模组页选曲，旧播放队列不会自动更新标签。从 `0.2.0` 或更早版本升级后，请完整重启播放器以清除旧播放 Hook。

## 构建与测试

需要 **JDK 25**，在项目目录执行：

```powershell
.\gradlew.bat test plugin
```

产物位于 `build/libs/*.spmod`，测试位于 `temp/tests/`，界面预览位于 `build/reports/ui-preview/`。

默认测试会跳过依赖宿主的检查。完整验证需指定播放器安装文件：

```powershell
.\gradlew.bat test "-PhostArchive=D:\Steam\steamapps\common\Salt Player for Windows\app\ffmpeg-x64.dll"
```

开发参考：[Workshop API](https://github.com/Moriafly/spw-workshop-api) · [开发文档](https://saltplayer.com/workshop/getting-started.html)
