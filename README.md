# SmartNote

SmartNote 是一个面向个人知识管理与轻量协作的智能笔记平台，当前已经具备笔记管理、Markdown 编辑、CRDT 协同编辑、知识图谱可视化、公开分享、评论、导出、管理员中心等核心能力。

## 当前功能

- 用户认证：注册、登录、JWT 鉴权、路由守卫、协作者登录门槛
- 知识管理：笔记本、笔记、标签、回收站、最近编辑、版本历史与回滚
- 编辑体验：CodeMirror 6 Markdown 编辑器、Yjs CRDT 协同编辑、在线协作者状态
- 搜索发现：关键词搜索、按笔记本/标签/日期范围筛选、结果高亮、筛选条件记忆
- 知识图谱：独立图谱页、节点拖拽、固定节点、关系筛选、按关系分组查看
- 分享协作：公开分享、提取码访问、评论、登录后协作编辑
- 文档导出：Markdown、PDF、Word
- 个人中心：昵称、简介、手机号、生日维护
- 管理后台：系统概览、用户管理、角色调整、账号启停、存储统计

## 技术栈

- 前端：Vue 3、TypeScript、Vite、Pinia、Vue Router、Ant Design Vue、CodeMirror 6、Yjs
- 后端：Spring Boot 3、Spring Security、JWT、Spring Data JPA、STOMP WebSocket
- 数据库：PostgreSQL
- 缓存：Caffeine

## 本地启动

### 后端

推荐把本地敏感配置写在 `backend/.env` 中，再用启动脚本运行。

Windows PowerShell:

```powershell
cd backend
.\start.ps1
```

Linux / macOS:

```bash
cd backend
chmod +x start.sh
./start.sh
```

`backend/.env.example` 可作为模板复制为你自己的 `backend/.env`。

### 前端

```bash
cd frontend
npm install
npm run dev
```

默认地址：

- 前端：`http://localhost:5173`
- 后端：`http://localhost:8080`

## 环境变量

后端常用环境变量示例：

```env
SERVER_PORT=8081
DB_URL=jdbc:postgresql://localhost:5432/smartnote
DB_USERNAME=postgres
DB_PASSWORD=your_database_password
OPENAI_API_KEY=your_api_key
OPENAI_BASE_URL=https://api.deepseek.com
SMARTNOTE_EXPORT_PDF_FONT_PATH=/usr/share/fonts/truetype/noto/NotoSansSC-Regular.ttf
```

前端开发代理可通过 `frontend/.env` 或命令行环境变量覆盖：

```env
VITE_API_PROXY_TARGET=http://localhost:8081
VITE_WS_PROXY_TARGET=ws://localhost:8081
```

## 安全说明

- 仓库中的 `backend/src/main/resources/application.yml` 与 `backend/src/main/resources/application.example.yml` 只保留占位符配置
- 本地敏感信息应放在已忽略的 `backend/.env` 中，不要提交到 Git
- 如果历史上曾将数据库密码或 API Key 推送到 GitHub，应立即轮换

## Linux 部署说明

- PDF 中文导出建议配置可读的 `.ttf` 字体路径，例如 `NotoSansSC-Regular.ttf`
- 若未配置 `SMARTNOTE_EXPORT_PDF_FONT_PATH`，系统会尝试使用可用系统字体，不再因字体注册失败直接中断导出

```bash
export SMARTNOTE_EXPORT_PDF_FONT_PATH=/usr/share/fonts/truetype/noto/NotoSansSC-Regular.ttf
```

## 验证命令

```bash
cd backend
mvn -DskipTests compile

cd ../frontend
npm run build
```

## 下一步建议

当前最值得继续推进的功能是“段落级评论”：

- 现有分享评论已经具备基础入口，但还停留在整篇笔记维度
- 你的项目已经有分享、协作、导出、知识图谱、管理员后台，段落级评论能明显增强协作深度
- 这个功能与现有分享体系和编辑器链路直接相关，落地成本比 AI 问答和搜索索引维护更可控
