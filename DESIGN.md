# OnChain OS — Design System (Apple Style)

> **AI Agent 专用设计规范**
> 本文件描述项目的完整视觉语言。任何 AI 编程 Agent 在生成或修改 UI 时，
> 必须优先读取此文件，确保与现有界面风格一致。
>
> 设计 Token 实现文件：`dashboard/src/main/resources/static/css/apple-design.css`
> 全局样式入口：`dashboard/src/main/resources/templates/layout.html`

---

## 1. Visual Theme & Atmosphere

**风格：Apple HIG — 简洁、呼吸感、材质感**

- 大量留白，内容密度适中，不堆砌信息
- 毛玻璃 Navbar（`backdrop-filter: blur(20px) saturate(180%)`），页面滚动时透视感强
- 卡片白底轻阴影，悬停时微微上浮（`translateY(-2px)`）
- 颜色饱和但不刺眼，使用 Apple 系统色（iOS 17 / macOS 14 sRGB 值）
- 字体：系统原生 SF Pro 字体栈，无需加载外部字体
- 数字/地址使用等宽字体（SF Mono / Menlo 字体栈）

**参考产品**：Apple.com、iOS Settings、macOS System Preferences、Apple Music

---

## 2. Color Palette

### 主要语义色（直接使用 CSS 变量，不要 hardcode 颜色值）

| 变量 | 值 | 用途 |
|------|-----|------|
| `var(--blue)` | `#007AFF` | 主交互色、链接、Active 状态 |
| `var(--green)` | `#34C759` | 上涨/正值/成功 |
| `var(--red)` | `#FF3B30` | 下跌/负值/错误 |
| `var(--yellow)` | `#FF9500` | 警告/中性提示（Apple Orange） |
| `var(--cyan)` | `#5AC8FA` | 信息/Teal |
| `var(--purple)` | `#AF52DE` | 特殊标签/高亮 |
| `var(--orange)` | `#FF9500` | 强调/徽章 |

### 文字色

| 变量 | 值 | 用途 |
|------|-----|------|
| `var(--t1)` | `#000000` | 主要正文 |
| `var(--t2)` | `rgba(60,60,67,0.60)` | 次要文字、标签 |
| `var(--t3)` | `rgba(60,60,67,0.30)` | 占位符、极弱提示 |

### 背景色

| 变量 | 值 | 用途 |
|------|-----|------|
| `var(--bg-page)` | `#F2F2F7` | 页面底色（Apple 次级背景） |
| `var(--bg-card)` | `#FFFFFF` | 卡片/列表行背景 |
| `var(--bg-nav)` | `rgba(255,255,255,0.72)` | 毛玻璃导航栏 |

### 淡色背景（用于 Tag、Badge、Wash）

每种主色对应一个 10-12% 透明度的淡色：

```css
var(--apple-blue-wash)    /* rgba(0,122,255,0.10) */
var(--apple-green-wash)   /* rgba(52,199,89,0.12) */
var(--apple-red-wash)     /* rgba(255,59,48,0.10) */
var(--apple-orange-wash)  /* rgba(255,149,0,0.10) */
var(--apple-purple-wash)  /* rgba(175,82,222,0.10) */
var(--apple-teal-wash)    /* rgba(90,200,250,0.12) */
```

---

## 3. Typography

### 字体栈

```css
/* UI 正文（无需 @import，使用系统字体） */
font-family: -apple-system, 'SF Pro Display', 'SF Pro Text',
             'Helvetica Neue', Arial, sans-serif;

/* 数字/地址/代码 */
font-family: 'SF Mono', 'Menlo', 'Monaco', 'Courier New', monospace;
```

### 字号规范（Apple Dynamic Type 映射）

| 场景 | 字号 | 字重 |
|------|------|------|
| 页面大标题 | 22-28px | 700-800 |
| 卡片标题 | 15px | 600 |
| 正文 / 表格 | 14px | 400-500 |
| 表头 / 标签 | 11-12px | 600-700，全大写，letter-spacing: 0.7px |
| 角标 / 极小 | 11px | 500 |

### 字重规范

- `400` — 正文
- `500` — 次级标签、Nav 链接
- `600` — 卡片标题、Active 状态、按钮
- `700` — 数值、重要标签
- `800` — 大数字（stat-card 值）

---

## 4. Component Styling

### Navbar（导航栏）

```css
/* 毛玻璃效果，position: sticky */
background: rgba(255, 255, 255, 0.72);
-webkit-backdrop-filter: blur(20px) saturate(180%);
backdrop-filter: blur(20px) saturate(180%);
border-bottom: 1px solid rgba(60, 60, 67, 0.29);
height: 58px;
```

### Card（卡片）

```css
background: #FFFFFF;
border: 1px solid #C6C6C8;       /* Apple opaque separator */
border-radius: 12px;              /* --radius-card */
box-shadow: 0 1px 3px rgba(0,0,0,.08), 0 1px 2px rgba(0,0,0,.05);

/* Hover */
box-shadow: 0 4px 12px rgba(0,0,0,.10), 0 2px 4px rgba(0,0,0,.06);
transform: translateY(-2px);
```

### Button（按钮）

```css
/* Primary */
background: #007AFF;
border-radius: 8px;   /* --radius-control */
font-weight: 600;
/* Hover: opacity .88, scale(1.01) */

/* Outline */
background: #fff; border: 1px solid #C6C6C8;
border-radius: 8px;
/* Hover: background apple-blue-wash, color #007AFF */
```

### Tag / Badge

```css
/* 统一圆角 999px（pill 形） */
border-radius: 999px;
padding: 2px 10px;
font-size: 12px; font-weight: 600;

/* 上涨 */  background: rgba(52,199,89,0.12);  color: #34C759;
/* 下跌 */  background: rgba(255,59,48,0.10);   color: #FF3B30;
/* 蓝色 */  background: rgba(0,122,255,0.10);   color: #007AFF;
/* 灰色 */  background: rgba(118,118,128,0.12); color: rgba(60,60,67,0.60);
```

### Tab（选项卡 — Apple Segmented Control 风格）

```css
/* 容器 */
background: rgba(118, 118, 128, 0.12);   /* --apple-fill3 */
border-radius: 10px; padding: 2px;

/* 激活项 */
background: #FFFFFF;
border-radius: 8px;
font-weight: 600;
box-shadow: 0 1px 3px rgba(0,0,0,.08);
```

### Table（表格）

```css
/* 表头 */
background: rgba(116,116,128,0.08);   /* --apple-fill4 */
color: rgba(60,60,67,0.30); font-size: 11px; font-weight: 700;
text-transform: uppercase; letter-spacing: 0.7px;

/* 行分隔 */
border-bottom: 1px solid rgba(118,118,128,0.12);

/* 行 Hover */
background: rgba(116,116,128,0.08);
```

---

## 5. Layout & Spacing

基于 4pt 网格（Apple 标准）：

| 层级 | 值 | 用途 |
|------|-----|------|
| `--space-2` | 8px | 行内元素间距 |
| `--space-3` | 12px | 小组件内 padding |
| `--space-4` | 16px | 标准内容边距 |
| `--space-5` | 20px | 卡片 padding |
| `--space-8` | 32px | Navbar 水平 padding |

页面最大宽度：`1640px`，水平 padding：`28px`，顶部 padding：`22px`

---

## 6. Border Radius

| 变量 | 值 | 用途 |
|------|-----|------|
| `--radius-xs` | 4px | 极小角标 |
| `--radius-sm` | 6px | Chain badge |
| `--radius-control` | 8px | 按钮、输入框 |
| `--radius-md` | 10px | Tab 容器 |
| `--radius-card` | 12px | 卡片、面板 |
| `--radius-lg` | 16px | 大卡片 |
| `--radius-full` | 999px | Pill / Tag |

---

## 7. Elevation & Shadows

Apple 阴影：低饱和、多层叠加、不显眼：

```css
/* Level 1 — 默认卡片 */
box-shadow: 0 1px 3px rgba(0,0,0,.08), 0 1px 2px rgba(0,0,0,.05);

/* Level 2 — Hover 卡片 */
box-shadow: 0 4px 12px rgba(0,0,0,.10), 0 2px 4px rgba(0,0,0,.06);

/* Level 3 — Popover / 下拉 */
box-shadow: 0 8px 24px rgba(0,0,0,.12), 0 3px 8px rgba(0,0,0,.07);

/* Level 4 — Modal */
box-shadow: 0 20px 60px rgba(0,0,0,.16), 0 8px 20px rgba(0,0,0,.09);
```

---

## 8. Animation & Motion

```css
/* 标准过渡 */
transition: all 150ms cubic-bezier(0.25, 0.46, 0.45, 0.94);

/* 弹性效果（按钮 hover） */
transition: transform 150ms cubic-bezier(0.34, 1.56, 0.64, 1.00);

/* 快速响应（50-150ms） */
transition: opacity 150ms ease;
```

---

## 9. Do's and Don'ts

### ✅ Do

- 使用 CSS 变量引用颜色，不 hardcode hex 值
- 表格行 Hover 用极淡的 `var(--apple-fill4)` 背景，不用深色
- 按钮 Hover 用 `opacity` + `scale` 而非颜色变深
- 链接/Active 状态统一用 `var(--blue)`（`#007AFF`）
- 卡片悬浮用 `translateY(-2px)` + 升级 shadow
- 毛玻璃只用于 Navbar（sticky header），不滥用

### ❌ Don't

- 不用 `box-shadow: 0 0 10px blue` 这种饱和色外发光
- 不用蓝色渐变按钮（`linear-gradient`），Primary 按钮统一纯 `#007AFF`
- 不用 Google Fonts（Inter 等），依赖系统字体
- 不在卡片上加 `border-left` 彩色粗线（除 stat-card 的细 accent bar）
- 不用半透明 border（如 `rgba(59,130,246,.15)`），改用不透明的 `#C6C6C8`
- Tag 不加 border，只用 wash 背景 + 对应色文字

---

## 10. Agent Prompt Guide

在生成新页面/组件时，请遵循以下提示：

```
使用 layout.html 的 Thymeleaf 模板（th:insert="${content}"）。
颜色全部用 CSS 变量，不写 hardcode hex。
卡片用 .card + .card-header + .card-body 结构。
表格用 .table，表头小写字母全大写处理（text-transform: uppercase）。
数字涨跌：正值 class="up"，负值 class="dn"。
状态标签用 .tag-up / .tag-dn / .tag-blue / .tag-gray。
选项卡用 .tab-row + .tab-item（Apple Segmented Control）。
统计块用 .stat-card + .sc-blue/.sc-green/.sc-red 等色变体。
地址/哈希用 class="mono addr"，onclick="copyAddr(this.textContent)"。
空状态用 .empty-state + .ei（emoji 图标）+ 说明文字。
```
