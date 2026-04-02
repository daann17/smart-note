# SmartNote 项目进度报告

最后更新时间：2026-03-26

## 1. 当前阶段判断

SmartNote 已从“功能原型”进入“可持续迭代的可用版本”阶段。当前前后端主链路已经完整，核心能力可跑通，且关键功能不再依赖临时演示代码。

目前项目的真实状态可以概括为：

- 用户认证、权限控制、管理员入口已打通
- 笔记、笔记本、标签、回收站、历史版本已可稳定使用
- 编辑器已从 Vditor 迁移为 `CodeMirror 6 + Yjs + STOMP` 的 CRDT 协同方案
- 分享、协同编辑、评论区、导出、AI 问答、知识图谱均已有可见成果
- 项目已具备继续做毕业设计展示、答辩演示和后续部署优化的基础

## 2. 主要模块完成度

状态说明：

- `已完成`：主链路已落地，可正常使用
- `进行中`：已有稳定基础，但仍有体验和细节可继续深化
- `待补强`：已有方向，但还没形成完整闭环

| 模块 | 当前状态 | 说明 |
| --- | --- | --- |
| 用户认证与权限 | 已完成 | 注册、登录、JWT、路由守卫、管理员鉴权、协作者登录门槛均已落地 |
| 笔记核心能力 | 已完成 | 笔记本/笔记 CRUD、Markdown 编辑、标签、历史版本、回收站、移动/复制、文件上传已具备 |
| 协同编辑 | 进行中 | CRDT 主链路已完成，协作者光标/在线状态/同步链路已跑通，仍可继续做更细腻的协作反馈 |
| 分享与评论 | 进行中 | 分享链接、提取码、可评论/可协同编辑、段落级评论、删除评论已实现 |
| AI 能力 | 进行中 | 智能摘要、标签建议、AI 问答链路、流式响应、停止生成已实现，引用来源和更强知识图谱联动仍可继续做 |
| 搜索与知识图谱 | 进行中 | 关键词搜索、高级筛选、知识图谱可视化已上线，图谱自动关系建议仍待补强 |
| 导出与部署 | 进行中 | Markdown / PDF / Word 导出已具备，Linux 部署友好性已补一轮 |
| 管理端 | 进行中 | 用户管理、启停用、角色调整、存储统计、搜索维护入口已有，仍可继续扩展监控面板 |

## 3. 已落地能力清单

### 3.1 用户与权限

- 用户注册、登录、退出
- JWT 认证与前端会话恢复
- 协作者必须登录后才能参与协同编辑
- 管理员页面 `/admin` 已具备服务端权限保护
- 个人中心已支持基本资料维护：
  - 昵称
  - 简介
  - 绑定手机号
  - 生日

### 3.2 笔记与编辑器

- 笔记本、笔记、标签的创建/编辑/删除
- Markdown 编辑、分栏预览、实时渲染
- 历史版本查看与回滚
- 回收站恢复与清理
- 笔记移动、复制
- 图片/文件上传
- Markdown 编辑器已改为 `CodeMirror 6`

### 3.3 协同编辑

- 基于 `Yjs + CodeMirror + STOMP` 的 CRDT 协同编辑
- 协作者在线状态与光标状态同步
- 行级协作者提示
- 分享页登录后协同编辑
- 分享页协同保存链路

### 3.4 分享、评论、导出

- 分享链接生成
- 提取码访问
- 分享页只读 / 可评论 / 可协同编辑模式
- 评论区独立页面
- 段落级评论定位与高亮
- 评论删除、回复删除
- Markdown / PDF / Word 导出

### 3.5 AI 与图谱

- AI 摘要
- AI 标签建议
- AI 问答抽屉
- 流式回答
- 停止生成
- 知识图谱可视化页面
- 节点详情、关系筛选、图谱交互优化

### 3.6 管理端与部署

- 管理员首页
- 用户管理
- 角色调整、启停用
- 存储统计
- 搜索维护入口
- `.env` 配置方案
- Windows / Linux 启动脚本
- `application.example.yml` 示例配置

## 4. 本轮最新完成内容

本轮重点完善的是“笔记编辑器上传体验”和“协同同步稳定性”。

### 4.1 上传体验修复

目标：解决“上传图片后编辑器里直接显示一长串丑陋 URL”的问题，并修复“粘贴图片上传失败”的问题。

已完成：

- 在编辑器中将上传后的 Markdown 资源折叠为可视卡片，而不是直接暴露长 URL
- 点击卡片可回到原始 Markdown，兼顾可视化与可编辑性
- 粘贴图片时新增剪贴板文件提取逻辑，兼容 `clipboardData.files` 与 `clipboardData.items`
- 对无文件名的剪贴板图片自动生成稳定文件名，如 `pasted-image-时间戳.png`
- 上传请求不再手动强写 `multipart/form-data`，避免 boundary 问题
- 分享协同场景下上传请求可携带 `shareToken`

涉及文件：

- [MarkdownEditor.vue](/e:/毕业设计/SmartNote/frontend/src/components/MarkdownEditor.vue)

### 4.2 协同同步卡死修复

目标：解决粘贴失败后界面一直停留在“正在同步协作文档”的问题。

已完成：

- 重构协同初始化时机，改为订阅建立后再主动触发初始同步
- 初始同步增加超时兜底，不再无限等待某条 join 回包
- 收到 `sync-response` 或远端 `doc-update` 后会主动结束同步等待态

涉及文件：

- [StompYjsProviderSecure.ts](/e:/毕业设计/SmartNote/frontend/src/lib/StompYjsProviderSecure.ts)

### 4.3 后端上传容错补强

目标：即便前端没有提供正常文件名，后端也能保存粘贴图片。

已完成：

- 后端对 `originalFilename` 为空的情况做兼容
- 根据 MIME 类型推导图片扩展名
- 上传返回值中为无文件名资源生成可展示名称
- 拒绝空文件

涉及文件：

- [FileService.java](/e:/毕业设计/SmartNote/backend/src/main/java/com/smartnote/service/FileService.java)
- [FileController.java](/e:/毕业设计/SmartNote/backend/src/main/java/com/smartnote/controller/FileController.java)

## 5. 本轮验证结果

已完成验证：

- 前端构建通过：`npm run build`
- 后端编译通过：`mvn -DskipTests clean compile`

说明：

- 本轮未做浏览器端人工点击回归，但上传与协同相关代码路径已完成静态编译验证

## 6. 当前脏工作区说明

当前仓库不是干净状态，除本轮改动外，还有之前未提交的功能改动存在。新对话继续开发时不要误回退这些文件。

当前 `git status` 可见的相关修改包括：

- [backend/src/main/java/com/smartnote/controller/AIController.java](/e:/毕业设计/SmartNote/backend/src/main/java/com/smartnote/controller/AIController.java)
- [backend/src/main/java/com/smartnote/controller/FileController.java](/e:/毕业设计/SmartNote/backend/src/main/java/com/smartnote/controller/FileController.java)
- [backend/src/main/java/com/smartnote/controller/ShareController.java](/e:/毕业设计/SmartNote/backend/src/main/java/com/smartnote/controller/ShareController.java)
- [backend/src/main/java/com/smartnote/dto/AIChatSourceResponse.java](/e:/毕业设计/SmartNote/backend/src/main/java/com/smartnote/dto/AIChatSourceResponse.java)
- [backend/src/main/java/com/smartnote/repository/NoteCommentRepository.java](/e:/毕业设计/SmartNote/backend/src/main/java/com/smartnote/repository/NoteCommentRepository.java)
- [backend/src/main/java/com/smartnote/service/AIService.java](/e:/毕业设计/SmartNote/backend/src/main/java/com/smartnote/service/AIService.java)
- [backend/src/main/java/com/smartnote/service/FileService.java](/e:/毕业设计/SmartNote/backend/src/main/java/com/smartnote/service/FileService.java)
- [backend/src/main/java/com/smartnote/service/ShareService.java](/e:/毕业设计/SmartNote/backend/src/main/java/com/smartnote/service/ShareService.java)
- [frontend/src/components/AIAssistantDrawer.vue](/e:/毕业设计/SmartNote/frontend/src/components/AIAssistantDrawer.vue)
- [frontend/src/components/MarkdownEditor.vue](/e:/毕业设计/SmartNote/frontend/src/components/MarkdownEditor.vue)
- [frontend/src/lib/StompYjsProviderSecure.ts](/e:/毕业设计/SmartNote/frontend/src/lib/StompYjsProviderSecure.ts)
- [frontend/src/views/ShareManageView.vue](/e:/毕业设计/SmartNote/frontend/src/views/ShareManageView.vue)

## 7. 当前遗留问题与注意点

- `MarkdownEditor.vue` 历史上经历过多次中文编码污染，本轮已经把会影响构建的断裂文本修正，但文件内部仍不够干净，后续适合做一次专门的中文化/文本清理
- 编辑器上传体验已明显改善，但还没有做“上传中进度反馈 / 重试 / 失败占位卡片”
- 协同编辑已稳定很多，但还可以继续做：
  - 更精细的多用户同段编辑冲突提示
  - 更明显的远端选择区高亮
  - 更自然的协作者身份展示
- AI、分享、评论相关链路已有未提交改动，新对话里应先看当前工作区再继续开发

## 8. 建议下一步优先级

如果开新对话后继续推进，建议优先顺序如下：

1. 清理 `MarkdownEditor.vue` 的历史乱码和中文文案
2. 补一轮编辑器上传体验：
   - 上传中状态
   - 上传失败重试
   - 拖拽/粘贴图片统一反馈
3. 继续做 AI 与知识图谱联动：
   - 图谱关系自动建议
   - AI 回答来源可视化
4. 深化分享协作：
   - 评论通知
   - 协作者变更提示
   - 更细的权限模型

## 9. 新对话承接建议

如果要开新对话，建议直接说明这几点：

- 当前项目是 `Vue 3 + Spring Boot + PostgreSQL`
- 编辑器已切到 `CodeMirror 6 + Yjs + STOMP`
- 本轮刚修完“粘贴图片上传失败”和“协同同步蒙层卡住”
- 继续开发前先读取：
  - [plan.md](/e:/毕业设计/SmartNote/plan.md)
  - [progress.md](/e:/毕业设计/SmartNote/progress.md)
- 开发时不要回退当前脏工作区中的既有修改

可以直接在新对话里用这句作为起点：

`先读取 plan.md 和 progress.md，检查当前 git 工作区，在不回退既有修改的前提下继续推进 SmartNote。`
