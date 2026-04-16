# DriftBottle

一个面向 `Spigot/Paper 1.18.2` 的漂流瓶插件。  
玩家可以把留言投进海里、从附近水域打捞别人投放的瓶子、继续匿名回信，并通过 GUI 管理自己的漂流瓶记录。

## 功能

- 配置化 Chest GUI，包含主菜单、我的漂流瓶、收件箱、资料页、黑名单、管理员后台
- 投放漂流瓶
- 随机打捞漂流瓶
- 只有附近存在足够深的水域时才允许投放 / 打捞
- 投放 / 打捞带可视化动画
- 打捞时瓶子会飞到玩家身边，必须由玩家实际拾取后才算成功
- 漂流瓶会话支持多轮回信
- 支持黑名单，避免再次打捞或继续互动
- 已封存漂流瓶可由瓶主直接打碎删除
- 收到回信、瓶子被打捞时支持可点击通知，点击后直达详情
- 管理员后台支持全服漂流瓶分页查看、筛选、强制封存、删除、玩家统计
- 支持 `Vault` 经济联动
- 默认 `SQLite`，可切换 `MySQL`
- 语言文件、GUI 布局、动画时长、视觉头颅、按钮样式均可配置

## 环境要求

- Java 17
- Spigot 1.18.2 或兼容核心
- 可选依赖：Vault 及任意兼容的经济插件

## 安装

1. 将插件 Jar 放入服务器 `plugins` 目录
2. 首次启动生成默认配置
3. 按需修改：
   - `config.yml`
   - `lang/zh_cn.yml` / `lang/en_us.yml`
   - `gui/*.yml`
   - `gui/admin/*.yml`
4. 如果需要经济功能，安装 `Vault` 和经济插件
5. 重启服务器或执行 `/driftbottle reload`

## 命令

- `/driftbottle`
- `/driftbottle throw`
- `/driftbottle salvage`
- `/driftbottle my`
- `/driftbottle inbox`
- `/driftbottle open <id>`
- `/driftbottle blacklist`
- `/driftbottle profile`
- `/driftbottle admin`
- `/driftbottle reload`

别名：

- `/dbottle`
- `/bottle`

## 权限

- `driftbottle.admin`
- `driftbottle.admin.view`
- `driftbottle.admin.manage`

## 配置说明

默认主配置在 `config.yml`。

重点配置项：

- `bottle.throw-cost`: 投放费用，走 Vault 扣费
- `bottle.salvage-reward`: 打捞奖励，走 Vault 发放
- `bottle.throw-cooldown-seconds`
- `bottle.salvage-cooldown-seconds`
- `bottle.max-active-bottles-per-player`
- `bottle.max-thread-replies`
- `bottle.water-search-radius`
- `bottle.water-min-depth`
- `bottle.visual-hover-ticks`
- `bottle.visual-flight-ticks`
- `bottle.salvage-pickup-wait-ticks`
- `bottle.visual-item.*`: 投放 / 打捞视觉头颅配置

## GUI 配置

所有 GUI 都使用 Shape 布局，资源文件位于：

- `src/main/resources/gui/*.yml`
- `src/main/resources/gui/admin/*.yml`

支持：

- 自定义装饰按钮
- 自定义分页按钮启用 / 禁用外观
- 自定义玩家头颅 / 纹理头颅
- 独立后台菜单

## 数据存储

支持两种模式：

- `SQLITE`
- `MYSQL`

默认使用 SQLite，本地开箱即用；多人长期服建议切到 MySQL。

## 构建

```bash
./gradlew build
```

输出包含 Shadow Jar。

