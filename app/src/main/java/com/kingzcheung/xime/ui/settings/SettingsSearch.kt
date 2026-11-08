package com.kingzcheung.xime.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * 设置搜索的一条索引：[title] 是页面上的原名，[path] 是去那里的路径，[target] 是导航路由，
 * [keywords] 放说明里的词和用户可能用的说法（如“长按”找到九键拂动）。新增设置时在这里补一条。
 * 点开后目标页上标着 [anchor] 的那一项滚到可见处并渐变高亮一次；那一项此刻不显示时（如按键音关着时的音量）
 * 退而高亮 [section] 那一组。[anchor] 为 null 的是整页，只打开页面。
 */
internal data class SettingsSearchEntry(
    val title: String,
    val path: String,
    val target: String,
    val keywords: String = "",
    val anchor: String? = title,
    val section: String? = null,
)

/** 字幕插件的那一页（字幕的设置都在这里）。 */
internal val CAPTION_PLUGIN_TARGET = "${SettingsRoutes.PluginSettings}/${com.kingzcheung.xime.ui.caption.CaptionPlugin.ID}"

/**
 * 字幕插件没装（或停用）时，字幕的几项设置不列出来（点进去是空页），换成一条「字幕」去插件市场装；装着就照常（11-05）。
 */
internal fun withCaptionPluginState(results: List<SettingsSearchEntry>, captionActive: Boolean): List<SettingsSearchEntry> {
    if (captionActive) return results
    val at = results.indexOfFirst { it.target == CAPTION_PLUGIN_TARGET }
    if (at < 0) return results
    val rest = results.filter { it.target != CAPTION_PLUGIN_TARGET }.toMutableList()
    rest.add(at.coerceAtMost(rest.size), SettingsSearchEntry("字幕", "系统 › 扩展 › 插件市场", SettingsRoutes.MarketPlugins,
        "字幕 插件", anchor = null))
    return rest
}

/** 布局编辑器是独立的 Activity，不在设置的导航图里。 */
internal const val SEARCH_TARGET_CUSTOM_LAYOUT = "activity:custom_layout"

private fun layoutPage(page: String) = "${SettingsRoutes.LayoutDisplay}?page=$page"

internal val SettingsSearchIndex: List<SettingsSearchEntry> = run {
    val input = "输入"
    val look = "外观"
    val smart = "智能"
    val system = "系统"
    val languages = "$input › 语言与方案"
    val chinesePath = "$languages › 中文输入"
    val englishPath = "$languages › 英文输入"
    val chinese = SettingsRoutes.ChineseInput
    val english = SettingsRoutes.EnglishInput
    val layouts = "$input › 键盘布局"
    val qwertyPath = "$layouts › 26 键"
    val ninePath = "$layouts › 九键与 14 键"
    val gestures = "$input › 按键与手势"
    val hardware = "$input › 物理键盘"
    val theme = "$look › 主题与外观"
    val candidates = "$look › 候选词"
    val effect = "$look › 按键效果"
    val dictionary = "$smart › 词库"
    val prediction = "$smart › 智能联想"
    val speech = "$smart › 语音转文本"
    val captionPage = "$system › 扩展 › 插件 › 字幕"
    val handwriting = "$smart › 手写"
    val clipboard = "$system › 剪贴板与同步"
    val clipboardPanel = "$clipboard › 剪贴板面板"
    val other = "$system › 其他设置"
    val backup = "$other › 备份与恢复"
    val performance = "$other › 性能与省电"
    val about = "$system › 关于 CyIME"
    val qwerty = LayoutInputCategory.Qwerty.route
    val nine = LayoutInputCategory.Nine.route
    val layoutIndex = SettingsRoutes.LayoutDisplay
    val gesturePage = layoutPage("gestures")
    val candidatePage = layoutPage("candidates")
    fun page(title: String, path: String, target: String, keywords: String) =
        SettingsSearchEntry(title, path, target, keywords, anchor = null)
    listOf(
        // 首页入口（整页）
        page("语言与方案", input, "languages", "语言 输入方案 键盘布局 切换方式 双拼 五笔 形码 笔画 仓颉 速成 注音 大千 日语 英语 九键 26键 14键 语言管理 中文 日文 英文 拼音 导入方案的语言 未标注语言 方案语言"),
        page("键盘布局", input, layoutIndex, "布局 26键 九键 14键 布局与显示 自定义布局 按键符号 分体"),
        page("按键与手势", input, gesturePage, "手势 角标 光标 空格 长按 上滑 下滑 滑动"),
        page("按键技巧", gestures, layoutPage("key_tips"), "技巧 说明 教程 帮助 Shift Ctrl 大写 锁定 快捷键 复制 粘贴 隐藏 功能 拖动 九键 数字键"),
        page("物理键盘", input, SettingsRoutes.HardwareKeyboard, "实体键盘 蓝牙键盘 外接键盘 硬键盘 语言切换快捷键 候选跟随 浮条 浮窗"),
        page("主题与外观", look, SettingsRoutes.Theme, "主题 皮肤 颜色 配色 深色 浅色 夜间 玻璃 背景 外观 视觉样式 定制"),
        page("候选词", look, candidatePage, "候选 候选栏 字号 候选注释 每页候选数"),
        page("按键效果", look, SettingsRoutes.KeyEffect, "按键音 声音 音效 振动 震动 反馈 发光 动画 炫光"),
        page("词库", smart, SettingsRoutes.Dictionary, "我的短语 导入的词库 分类词库 删掉的候选 查词 个人词库 用户词 自定义短语 方案词库 词典 词库管理 导入词库"),
        page("智能联想", smart, SettingsRoutes.SmartPrediction, "联想 预测 个人学习 模型增强 下一个词"),
        page("语音转文本", smart, SettingsRoutes.SpeechToText, "语音 识别 麦克风 说话 听写 在线 本地 语音输入 开关 字幕 语音在哪 语音输入在哪 怎么说话"),
        page("手写", smart, SettingsRoutes.Handwriting, "手写 写字 笔迹 公式 手写模型"),
        page("剪贴板与同步", system, SettingsRoutes.ClipboardSync, "剪贴板 同步 验证码 短信 复制 电脑 Windows 互联"),
        page("扩展", system, SettingsRoutes.Market, "下载 输入方案 模型 插件 商店 扩展商店"),
        page("其他设置", system, SettingsRoutes.Other, "其他 更多 备份 性能 省电 界面语言 显示语言 Language"),
        page("备份与恢复", other, SettingsRoutes.Backup, "备份 恢复 导出 导入 本地备份 换机 迁移"),
        page("性能与省电", other, SettingsRoutes.Performance, "性能 省电 内存 卡顿 高配 中配 低配 自动 动效 炫光 语音常驻 释放 SenseVoice Paraformer"),
        page("按设备推荐配置", "配置选择", SettingsRoutes.SetupGuide, "推荐 自动 检测 手机 平板 配置 引导 向导 不会设置 不懂 省空间 删模型 体积 太大 占空间 语音模型 九键模型 怎么选 打字习惯 全拼 双拼 小鹤 自然码 微软双拼 五笔 模糊音 平翘舌 前后鼻音 字幕 桌面图标"),
        page("关于 CyIME", system, SettingsRoutes.About, "版本 开发者 联系 许可 检查更新 最新版本 源代码"),
        // 输入 › 语言与方案
        SettingsSearchEntry("语言键切换方式", languages, "languages", "语言键 切换语言 中英切换 循环切换 指定语言 英文 地球键 点按语言键"),
        SettingsSearchEntry("繁体输出", chinesePath, chinese, "繁体 繁體 正体 简繁 简繁切换 简体 台湾 转换"),
        SettingsSearchEntry("生僻字", chinesePath, chinese, "生僻字 罕用字 缺字 打不出 大字表 扩展字 字库 冷僻字"),
        SettingsSearchEntry("时间日期候选", chinesePath, chinese, "时间 日期 星期 今天 几点 当前时间 当前日期 年月日 联想 候选 ts 时间戳 unix"),
        SettingsSearchEntry("网址、邮箱框自动切英文", englishPath, english, "网址 邮箱 链接 地址栏 url email 自动英文 自动切英文 切成英文 变英文 中文 换软件 输入框"),
        SettingsSearchEntry("选词后自动加空格", englishPath, english, "英文 英语 空格 自动空格 自动加空格 补全 标点 iOS 苹果 西班牙语"),
        SettingsSearchEntry("标点后自动加空格", englishPath, english, "英文 英语 空格 自动空格 自动加空格 标点 逗号 句号 问号 英文标点 标点空格 西班牙语"),
        SettingsSearchEntry("打 @ 时给邮箱后缀", englishPath, english, "邮箱 邮件 电子邮件 email 后缀 @ 艾特 qq.com 163 gmail 补全 联想 域名"),
        SettingsSearchEntry("候选里显示表情", chinesePath, chinese, "表情 emoji 联想表情 候选表情 开心 爱心 笑脸"),
        SettingsSearchEntry("打符号名出符号", chinesePath, chinese, "符号 符号名 加号 减号 等号 问号 括号 引号 百分号 标点 打字出符号 数学符号"),
        SettingsSearchEntry("希腊字母", chinesePath, chinese, "希腊 希腊字母 西塔 派 圆周率 阿尔法 贝塔 伽马 德尔塔 兰姆达 西格玛 欧米伽 xita pi alpha theta 数学符号 物理符号"),
        page("中文输入", languages, chinese, "中文 拼音 繁体 生僻字 时间日期 表情 符号名 希腊字母 纠错 模糊音 候选"),
        page("英文输入", languages, english, "英文 英语 网址 邮箱 自动空格 空格 邮箱后缀 西班牙语"),
        page("已安装的方案", languages, SettingsRoutes.Schema, "下载 导入 启停 方案 部署 输入资源 排序 方案资源 启用14键 中文14键"),
        page("双拼方案", languages, SettingsRoutes.DoublePinyin, "双拼 自定义双拼 首道双拼 小鹤双拼 自然码 智能ABC 拼音加加 微软双拼 分号 九键双拼 14键双拼 键位 韵母 零声母 新建 编辑 小鹤14 小鹤14键 双拼14键"),
        SettingsSearchEntry("双拼键位提示", theme, SettingsRoutes.Theme, "双拼 键位 提示 键位图 记不住 韵母 声母 显示 键盘上 主题 双拼主题 小鹤 自然码 角标 一起显示 和角标一起显示", section = "角标与气泡"),
        SettingsSearchEntry("ing 放在 Shift 键上", "$languages › 双拼方案", SettingsRoutes.DoublePinyin, "微软双拼 分号 ing Shift 大写键 分词键 L 最右边 布局 搜狗 讯飞 微信"),
        SettingsSearchEntry("双拼简拼", "$languages › 双拼方案", SettingsRoutes.DoublePinyin, "简拼 双拼简拼 首字母 声母 缩写 只打声母 全拼简拼 小鹤 自然码 微软双拼 zgrm", section = "通用"),
        SettingsSearchEntry("编码显示双拼键", "$languages › 双拼方案", SettingsRoutes.DoublePinyin, "编码 显示 双拼键 原始按键 按键 字母 全拼 拼音 ul pb 小鹤 九键", section = "通用"),
        // 输入 › 键盘布局
        page("26 键", layouts, qwerty, "26键 全键盘 全拼 qwerty 中英混输 滑行输入"),
        page("九键与 14 键", layouts, nine, "九键 9键 14键 拂动 假名 触发距离"),
        SettingsSearchEntry("布局管理", layouts, SEARCH_TARGET_CUSTOM_LAYOUT, "自定义布局 自定义按键布局 切换栏 隐藏布局 布局排序 新建布局 复制 编辑按键排列", anchor = null),
        SettingsSearchEntry("按键符号", layouts, layoutIndex, "角标 符号编辑 字母键符号 上下角标 下滑符号 14键符号 自定义布局符号 侧栏 笔画 短语 常用符号栏 手写符号 数字行 滑动动作 zxcv 撤销 剪切 复制 粘贴 全选 段首 段尾"),
        SettingsSearchEntry("标点上屏首选", layouts, layoutIndex, "标点 逗号 句号 问号 首选 第一个候选 上屏 候选上屏 打逗号 选词 搜狗"),
        SettingsSearchEntry("输入成对符号", layouts, layoutIndex, "成对 配对 括号 引号 书名号 自动补全 闭合 光标中间"),
        SettingsSearchEntry("回车发送", layouts, layoutIndex, "回车 发送 换行 enter 回车键发送消息 微信 QQ 直接发送 Ctrl+回车"),
        SettingsSearchEntry("分体键盘", layouts, layoutIndex, "分体 分离 拆分 平板 两手 双手 间距"),
        SettingsSearchEntry("右半边也放 G 键", layouts, layoutIndex, "分体 右边 右手 G键 g 重复 两个G 双G 中排 对齐"),
        SettingsSearchEntry("填满留空处", layouts, layoutIndex, "一体 一体式 镂空 中缝 两侧 空白 留空 透明 遮住 背景 连起来 分体"),
        SettingsSearchEntry("加宽按键", layouts, layoutIndex, "按键宽度 键宽 宽键 按键间距 键间距 间隙 缝 留白 边距 误触 按错 手机"),
        SettingsSearchEntry("合并键均匀排布", layouts, layoutIndex, "合并键 合并 14键 17键 18键 20键 自定义布局 键宽 均匀 等宽 单键 放宽 对齐 小屏 窄屏"),
        SettingsSearchEntry("数字框自动数字键盘", layouts, layoutIndex, "数字 验证码 号码 电话 数字键盘 自动弹出"),
        SettingsSearchEntry("数字出短语", layouts, layoutIndex, "数字 短语 我的短语 自定义短语 邮箱 网址 号码 数字串 数字键盘 候选"),
        SettingsSearchEntry("联想常用数字", layouts, layoutIndex, "数字 常用数字 号码 电话 联想 学习 数字键盘 候选"),
        SettingsSearchEntry("数字行", layouts, layoutIndex, "数字行 数字栏 一排数字 顶部数字 第五行 平板 1234567890 数字顺序 数字位置 自定义数字"),
        page("键盘尺寸与位置", layouts, SettingsRoutes.KeyboardResize, "尺寸 大小 高度 宽度 调整尺寸 键盘调节 悬浮 浮动 分体 透明度 底部留白 单手 位置 旋转"),
        page("锁定悬浮键盘位置和大小", layouts, SettingsRoutes.KeyboardResize, "固定悬浮键盘 防误拖 锁住 解锁 底部短横条 长按 宽高 四角"),
        SettingsSearchEntry("手势导航时键盘贴底", layouts, layoutPage("bottom_gap"), "键盘底部留白 下边距 底部空白 贴到底 手势导航栏 忽略窗口边衬区 忽略系统窗口边衬区 边衬区 insets 全面屏手势 底部高度"),
        page("底部留白校准", layouts, layoutPage("bottom_gap"), "底部空白 多一行 全面屏手势 导航栏 挡住 按键重叠 底边拖不动 调低 抬高 恢复系统建议"),
        page("工具栏与菜单排列", layouts, SettingsRoutes.ToolbarArrange, "工具栏 菜单 排列 顺序 入口 图标 按钮 自定义工具栏"),
        SettingsSearchEntry("邻键纠错", chinesePath, chinese, "纠错 打错 误触 全拼 双拼 按错 相邻 26键", section = "拼音纠错"),
        SettingsSearchEntry("双拼邻键纠错", chinesePath, chinese, "双拼 纠错 打错 误触 按错 相邻 小鹤 自然码", section = "拼音纠错"),
        SettingsSearchEntry("顺序纠错", chinesePath, chinese, "纠错 顺序 打反 颠倒 换位 调换 字母顺序 交换 ain ian", section = "拼音纠错"),
        SettingsSearchEntry("多键纠错", chinesePath, chinese, "纠错 多键 多打 多按 重复按 多一个字母", section = "拼音纠错"),
        SettingsSearchEntry("漏键纠错", chinesePath, chinese, "纠错 漏键 漏打 少打 少一个字母 缺字母", section = "拼音纠错"),
        SettingsSearchEntry("中英混输", qwertyPath, qwerty, "中英混打 混输 混打 夹英文 拼音里打英文 中文里打英文 nihaohello"),
        SettingsSearchEntry("九键按数字也模糊", chinesePath, chinese, "九键模糊音 九宫格模糊音 九键 模糊音 数字 直接模糊 平翘舌 前后鼻音 性能 卡顿", section = "模糊音"),
        SettingsSearchEntry("模糊音也用于双拼", chinesePath, chinese, "双拼模糊音 双拼 模糊音 小鹤 自然码 in ing 前后鼻音", section = "模糊音"),
        SettingsSearchEntry("模糊音", chinesePath, chinese, "模糊音 模糊 平翘舌 前后鼻音 z zh c ch s sh n l f h r an ang en eng in ing 分不清 口音 方言 九键模糊音 九宫格 14键模糊音 14 键 双拼"),
        SettingsSearchEntry("滑行输入", qwertyPath, qwerty, "滑行 划词 滑动输入 手势输入 英文 整词 swype"),
        SettingsSearchEntry("中文滑行输入", qwertyPath, qwerty, "滑行 划词 中文滑行 拼音滑行 双拼滑行 手势输入 swype"),
        SettingsSearchEntry("逐字删除中文滑行", qwertyPath, qwerty, "滑行 划词 删除 退格 拼音 音节 中文"),
        SettingsSearchEntry("滑行时实时显示词", qwertyPath, qwerty, "滑行 实时 预览 显示 抬手 gboard 划词 swype"),
        SettingsSearchEntry("三行字母错位", qwertyPath, qwerty, "错位 排布 实体键盘 对齐 手机排布 字母行 偏移"),
        SettingsSearchEntry("空格两侧的标点", qwertyPath, qwerty, "逗号键 句号键 空格 标点 全角 半角 宽度"),
        SettingsSearchEntry("空格左侧逗号键", qwertyPath, qwerty, "逗号 空格 加宽 标点"),
        SettingsSearchEntry("英文句首自动大写", qwertyPath, qwerty, "英文键盘 首字母大写 首句大写 句首大写 自动大写"),
        SettingsSearchEntry("底排标点固定上滑", qwertyPath, qwerty, "逗号 句号 半角 点号 向上拖 上拉 符号 屏幕边缘"),
        SettingsSearchEntry("空格右侧句号键", qwertyPath, qwerty, "句号 空格 标点"),
        SettingsSearchEntry("逗号键分词", qwertyPath, qwerty, "分词 分词键 隔音符 分隔符 单引号 撇号 音节 西安 xian 逗号"),
        SettingsSearchEntry("Shift 键分词", qwertyPath, qwerty, "分词 分词键 隔音符 分隔符 Shift 大小写 大写键 音节 西安 xian"),
        SettingsSearchEntry("长音键", qwertyPath, qwerty, "长音 ー 伸ばし棒 日语 日文 罗马字 L 键 Shift 大写键 コーヒー"),
        SettingsSearchEntry("拖到候选选词的距离", ninePath, nine, "九键 0键 零键 拖动 拖拽 托转 选词 候选 距离 阈值 Shift 百分比 拖线"),
        SettingsSearchEntry("拂动选字母", ninePath, nine, "九键 拂动 滑动 长按 数字 字母 字母菜单 上下左右"),
        SettingsSearchEntry("九键滑行输入", ninePath, nine, "九宫格 九鍵 滑行 连划 滑动拼音 连续输入 手势"),
        SettingsSearchEntry("左右滑动选字母", ninePath, nine, "14键 双字母键 滑动 选字母 小鹤 双拼 左滑 右滑 自建 合并 18键"),
        SettingsSearchEntry("滑动触发距离", ninePath, nine, "距离 灵敏度 误触 拂动 滑动 假名 dp"),
        SettingsSearchEntry("拂动时显示方向圆盘", ninePath, nine, "圆盘 方向 箭头 拂动 提示 假名 日语"),
        SettingsSearchEntry("假名方向提示", ninePath, nine, "日语 日文 假名 灰色小字 方向"),
        // 输入 › 按键与手势
        SettingsSearchEntry("按键角标的符号和数字", gestures, gesturePage, "角标 角标输入方式 滑动输入 长按输入 符号 数字 上滑 下滑 输入方式 手势预设 预设"),
        SettingsSearchEntry("长按弹出字母气泡", gestures, gesturePage, "长按 字母 大写 小写 音调 气泡 弹出 拼音声调 九键 字母键长按弹出大小写 字母菜单 滑动＋字母气泡", section = "按键角标的符号和数字"),
        SettingsSearchEntry("反转上下滑方向", gestures, gesturePage, "反转 方向 上滑 下滑 上拉 下拉 删除键 清空 撤销"),
        SettingsSearchEntry("左右滑动删除", gestures, gesturePage, "删除键 左滑 右滑 滑动删除 按距离 向后删 Delete 光标后 连删 长按 误触 撤回 恢复", section = "删除键"),
        SettingsSearchEntry("长按删除速度", gestures, gesturePage, "删除键 长按 按住 连删 连续删除 速度 快慢 振动 震动 退格 回删", section = "删除键"),
        SettingsSearchEntry("删除键单独反向", gestures, gesturePage, "删除键 反向 反转 对调 上滑 下滑 清空 撤回 撤销 方向", section = "删除键"),
        SettingsSearchEntry("清空滑动距离", gestures, gesturePage, "删除键 清空 撤回 撤销 上滑 下滑 距离 灵敏度 滑不到 屏幕边缘", section = "删除键"),
        SettingsSearchEntry("打字中清空整个输入框", gestures, gesturePage, "清空 删除键 上滑 拼音 候选 输入框 全部 一次 跳过候选", section = "删除键"),
        SettingsSearchEntry("滑动清空时跳过联想", gestures, gesturePage, "清空 正文 上滑 删除键 联想", section = "删除键"),
        SettingsSearchEntry("只清空光标前的内容", gestures, gesturePage, "清空 删除键 上滑 下滑 光标前 光标后 保留 部分 一半", section = "删除键"),
        SettingsSearchEntry("长按语言键切换输入法", gestures, gesturePage, "语言键 地球键 长按 切换输入法 选择输入法 其他输入法 系统输入法 KeePassDX 密码键盘", section = "时长与距离"),
        SettingsSearchEntry("长按语言键弹出列表", gestures, gesturePage, "语言键 切换语言 长按 时长 语言列表 秒", section = "时长与距离"),
        SettingsSearchEntry("符号键松手打开符号的时限", gestures, gesturePage, "符号键 !@# Ctrl 控制键 快捷键 复制 粘贴 剪切 撤销 全选 长按 时长 时限 过期 秒", section = "时长与距离"),
        SettingsSearchEntry("键盘面板滑动光标", gestures, gesturePage, "光标 移动 滑动 整个键盘 键盘滑动 滑动移动光标 整个键盘滑动移动光标 关闭", section = "光标与空格"),
        SettingsSearchEntry("底部留白滑动移动光标", gestures, gesturePage, "光标 移动 滑动 底部 留白 抬高 键盘下面 空白 选择 扩选", section = "光标与空格"),
        SettingsSearchEntry("长按空格多久开始", gestures, gesturePage, "长按空格 时长 秒 延迟 移动光标 连续输入空格 开关语音 说话", section = "时长与距离"),
        SettingsSearchEntry("从中间滑到边缘走几行", gestures, gesturePage, "光标 一行 行数 灵敏 灵敏度 速度 平板 大屏 滑到边缘", section = "时长与距离"),
        SettingsSearchEntry("光标每移动一字所需距离", gestures, gesturePage, "光标 距离 灵敏 灵敏度 速度 dp 毫米", section = "时长与距离"),
        SettingsSearchEntry("移动光标时振动", gestures, gesturePage, "光标 振动 震动 触感", section = "光标与空格"),
        SettingsSearchEntry("移动光标时播放按键音", gestures, gesturePage, "光标 声音 按键音", section = "光标与空格"),
        SettingsSearchEntry("长按空格", gestures, gesturePage, "空格 长按 移动光标 连续空格 语音 语音开关 按住说话 麦克风 上滑取消 松手取消 取消语音", section = "光标与空格"),
        // 输入 › 物理键盘
        SettingsSearchEntry("Ctrl + 空格", hardware, SettingsRoutes.HardwareKeyboard, "ctrl 空格 语言切换 快捷键 循环切换"),
        SettingsSearchEntry("单独轻按 Shift", hardware, SettingsRoutes.HardwareKeyboard, "shift 中英切换 快捷键 语言切换"),
        SettingsSearchEntry("工具快捷键", hardware, SettingsRoutes.HardwareKeyboard, "快捷键 ctrl alt 标点 剪贴板 语音 alt+v alt+h"),
        SettingsSearchEntry("候选翻页", hardware, SettingsRoutes.HardwareKeyboard, "翻页 上一页 下一页 减号 等号 中括号 逗号 句号"),
        SettingsSearchEntry("候选跟随光标", hardware, SettingsRoutes.HardwareKeyboard, "候选 跟随 光标 浮窗 浮条 位置"),
        SettingsSearchEntry("联想也显示在候选窗", hardware, SettingsRoutes.HardwareKeyboard, "联想 候选 位置 浮窗 工具条 跳 换位置 便携键盘 蓝牙键盘"),
        SettingsSearchEntry("自动避开输入区域", hardware, SettingsRoutes.HardwareKeyboard, "避让 遮挡 输入框 浮窗 浮条"),
        SettingsSearchEntry("靠边停靠", hardware, SettingsRoutes.HardwareKeyboard, "停靠 吸附 边缘 竖条 浮窗 浮条"),
        SettingsSearchEntry("底部候选", hardware, SettingsRoutes.HardwareKeyboard, "三星 通栏 三星式通栏 底边使用三星式通栏 底部 底边 候选栏 整宽 编号 停靠", section = "靠边停靠"),
        SettingsSearchEntry("游戏直通", hardware, SettingsRoutes.HardwareKeyboard, "游戏 打游戏 玩游戏 wasd 按键被吞 收不到 不弹键盘 蓝牙键盘 otg 外接键盘 实体键盘"),
        // 外观 › 主题与外观
        SettingsSearchEntry("显示模式", theme, SettingsRoutes.Theme, "深色 浅色 跟随系统 夜间 暗色 黑色 白色 明暗 日间"),
        SettingsSearchEntry("玻璃效果", theme, SettingsRoutes.Theme, "玻璃 毛玻璃 透明 材质 磨砂", section = "材质效果"),
        SettingsSearchEntry("透明度与模糊", theme, SettingsRoutes.Theme, "透明度 模糊 模糊强度 背景不透明度 按键不透明度 玻璃", section = "材质效果"),
        SettingsSearchEntry("液态玻璃", theme, SettingsRoutes.Theme, "液态 液体 玻璃 高光 亮边 反光 iOS 苹果 Liquid Glass", section = "材质效果"),
        SettingsSearchEntry("浅色遮罩", theme, SettingsRoutes.Theme, "遮罩 变暗 暗度 压暗 变淡 发白 图片太暗 图片太淡 图片看不清 背景亮度 背景暗度 白天", section = "背景图片"),
        SettingsSearchEntry("深色遮罩", theme, SettingsRoutes.Theme, "遮罩 变暗 暗度 压暗 变淡 图片太暗 图片太淡 图片看不清 背景亮度 背景暗度 黑夜 夜间", section = "背景图片"),
        SettingsSearchEntry("配色方案", theme, SettingsRoutes.Theme, "配色 颜色 主题色 皮肤 预览"),
        SettingsSearchEntry("用自己的图片", theme, SettingsRoutes.Theme, "背景图 背景图片 自定义背景 图片 照片 相册 壁纸 我的图片 换背景 调整图片 裁剪 缩放 对齐 留白 删除图片", section = "背景图片"),
        SettingsSearchEntry("动态背景", theme, SettingsRoutes.Theme, "动图 动态壁纸 背景动 会动 缓动 放大 缩小 推远 拉近 平移 左右移动 轮换 ken burns 背景动画", section = "背景图片"),
        SettingsSearchEntry("轮播图片", theme, SettingsRoutes.Theme, "轮播 多张图片 多图 幻灯片 图片切换 换图 相册 背景图", section = "背景图片"),
        SettingsSearchEntry("视觉样式", theme, SettingsRoutes.Theme, "样式 圆角 扁平 立体 材质"),
        SettingsSearchEntry("按键形状", theme, SettingsRoutes.Theme, "形状 圆角 方形 方角 直角 方块 键帽 按键外形 iOS"),
        SettingsSearchEntry("按键字号", theme, SettingsRoutes.Theme, "字号 字体 文字大小 按键文字 字体大小"),
        SettingsSearchEntry("键帽字母大小写", theme, SettingsRoutes.Theme, "大写 小写 大小写 字母 键帽 按键文字 显示 干净 简洁 中英 中文 英文 分辨 区分 搜狗 盲打 大写QWER 字母键显示大写 中文键盘字母显示大写"),
        SettingsSearchEntry("上方角标提示", gestures, gesturePage, "角标 提示 符号 显示 上方 干净 干净模式 简洁 隐藏"),
        SettingsSearchEntry("下方角标提示", gestures, gesturePage, "角标 提示 符号 显示 下方 干净 干净模式 简洁 隐藏"),
        SettingsSearchEntry("角标不透明度", theme, SettingsRoutes.Theme, "角标 透明度 不透明度 淡 变淡 减淡 颜色 灰 玻璃 层次 突出 主字"),
        SettingsSearchEntry("功能键不显示提示字", theme, SettingsRoutes.Theme, "空格 方案名 方案名称 隐藏 不显示 删除键 清空 重输 提示 小字 功能键 干净"),
        SettingsSearchEntry("点按弹出气泡", theme, SettingsRoutes.Theme, "气泡 预览 弹出 按键放大"),
        SettingsSearchEntry("键盘顶部样式", theme, SettingsRoutes.Theme, "圆角 顶部 顶部圆角 上圆角 直角 方角 关圆角 关闭圆角 柔化 柔化顶部边缘 羽化 渐变 边缘 上沿 割裂 衔接 玻璃 毛玻璃"),
        SettingsSearchEntry("底部圆角与透明导航栏", theme, SettingsRoutes.Theme, "圆角 导航栏 底部 直角 键盘底部样式"),
        SettingsSearchEntry("图标设置", theme, SettingsRoutes.Theme, "桌面图标 应用图标 底板 图标样式 图标联动 跟随主题色 跟随视觉样式 自己选 同步图标 恢复原有图标"),
        // 外观 › 候选词
        SettingsSearchEntry("候选字大小", candidates, candidatePage, "候选 字号 字体大小 文字大小"),
        SettingsSearchEntry("候选词间距加宽", candidates, candidatePage, "候选 间距 间隔 太挤 密度 单字 宽松"),
        SettingsSearchEntry("候选栏取消按钮", candidates, candidatePage, "取消 左箭头 取消输入"),
        SettingsSearchEntry("联想关闭键用叉号", candidates, candidatePage, "联想 关闭联想 关闭键 叉 叉号 圆叉 左箭头"),
        SettingsSearchEntry("候选栏按钮按压高亮", candidates, candidatePage, "按住 反馈 取消键 关闭 展开箭头"),
        SettingsSearchEntry("候选注释", candidates, candidatePage, "注释 显示 自动 隐藏 候选显示"),
        SettingsSearchEntry("候选注释内容", candidates, candidatePage, "注释 拼音 释义 翻译 编码 中英 英文意思 词典 英文 单词 英文单词 选英文 出英文 上屏英文 打英文 中译英 下一项 长按下一项 shift"),
        SettingsSearchEntry("编码显示", candidates, candidatePage, "预编辑 输入框 候选栏 编码位置 拼音显示 拼音框 拼音浮框 拼音气泡"),
        SettingsSearchEntry("显示拼音编辑栏", candidates, candidatePage, "关闭 隐藏 顶部 拼音浮条 编码"),
        SettingsSearchEntry("拼音放在键盘内", candidates, candidatePage, "候选栏内部 拼音 编辑 遮挡 底栏"),
        SettingsSearchEntry("拼音气泡不透明度", candidates, candidatePage, "拼音 气泡 透明度 不透明度 透明 看不清 实底 浮条"),
        SettingsSearchEntry("每页候选词数", candidates, candidatePage, "候选数量 分页 每页 个数"),
        // 外观 › 按键效果
        SettingsSearchEntry("启用按键音", effect, SettingsRoutes.KeyEffect, "按键音 声音 音效 静音 打字声"),
        SettingsSearchEntry("按键音色", effect, SettingsRoutes.KeyEffect, "音色 Rain75 紫薇轴 线性轴 机械键盘 实体键盘 按键声音 音效", section = "按键音效"),
        SettingsSearchEntry("音量大小", effect, SettingsRoutes.KeyEffect, "音量 声音大小 按键音", section = "按键音效"),
        SettingsSearchEntry("录屏时包含按键音", effect, SettingsRoutes.KeyEffect, "录屏 录制 屏幕录制 按键音 媒体音量 声音", section = "按键音效"),
        SettingsSearchEntry("触觉反馈模式", effect, SettingsRoutes.KeyEffect, "振动 震动 触感 触觉 跟随系统 关闭振动 马达"),
        SettingsSearchEntry("按键抬起振动", effect, SettingsRoutes.KeyEffect, "抬起 松手 振动 震动", section = "振动反馈"),
        SettingsSearchEntry("按键振动时长", effect, SettingsRoutes.KeyEffect, "振动 震动 时长 强度", section = "振动反馈"),
        SettingsSearchEntry("长按振动时长", effect, SettingsRoutes.KeyEffect, "长按 振动 震动 时长", section = "振动反馈"),
        SettingsSearchEntry("按键振动振幅", effect, SettingsRoutes.KeyEffect, "振动 震动 振幅 强度 力度 马达", section = "振动反馈"),
        SettingsSearchEntry("长按振动振幅", effect, SettingsRoutes.KeyEffect, "长按 振动 震动 振幅 强度", section = "振动反馈"),
        SettingsSearchEntry("按键动效", effect, SettingsRoutes.KeyEffect, "动效 动画 缩放 回弹 键帽"),
        SettingsSearchEntry("键内炫光", effect, SettingsRoutes.KeyEffect, "炫光 发光 光效 渐变 彩虹"),
        // 智能 › 词库
        page("导入的词库", dictionary, SettingsRoutes.CellDictionaries, "分类词库 细胞词库 搜狗 scel txt json 导入词库 下载词库 本地导入 删除词库 自动更新词库 词库更新 在线词库 新词"),
        SettingsSearchEntry("我的短语", dictionary, SettingsRoutes.Dictionary, "自定义短语 短语 快捷短语 常用语 编码", anchor = null),
        SettingsSearchEntry("查词", dictionary, SettingsRoutes.Dictionary, "方案词库 词库 词条 编码 查找", anchor = null),
        SettingsSearchEntry("删掉的候选", dictionary, SettingsRoutes.Dictionary, "屏蔽的候选 屏蔽 恢复 候选 九键 删词 忘记", anchor = null),
        SettingsSearchEntry("贡献词库", dictionary, SettingsRoutes.DictContribute, "贡献 分享 上传 词库 常用词 改进词库 用户词库 导出", anchor = null),
        // 智能 › 智能联想
        SettingsSearchEntry("启用智能联想", prediction, SettingsRoutes.SmartPrediction, "联想 开关 预测 基础联想"),
        SettingsSearchEntry("空格上屏联想候选", prediction, SettingsRoutes.SmartPrediction, "空格 联想 上屏", section = "功能开关"),
        SettingsSearchEntry("退格直接删字", gestures, gesturePage, "退格 删除 删除键 删字 联想 收起联想 按两下", section = "删除键"),
        SettingsSearchEntry("删字后给同音字", gestures, gesturePage, "退格 删除 删除键 删错 恢复 撤销 同音字 纠错", section = "删除键"),
        SettingsSearchEntry("连续联想", prediction, SettingsRoutes.SmartPrediction, "连续 联想 推理", section = "功能开关"),
        SettingsSearchEntry("单次联想", prediction, SettingsRoutes.SmartPrediction, "单次 联想 停止", section = "功能开关"),
        SettingsSearchEntry("无痕模式", prediction, SettingsRoutes.SmartPrediction, "无痕 隐私 不学习 不记录 剪贴板 私密 密码"),
        SettingsSearchEntry("删掉刚打的词不学习", prediction, SettingsRoutes.SmartPrediction, "自学习 学习 误触 怪词 删除 退格 删掉 不记 词库 调频", section = "无痕"),
        SettingsSearchEntry("用户学习数据", prediction, SettingsRoutes.SmartPrediction, "学习 记忆 个人语料 导出 导入 保存 统计 学习文件"),
        // 智能 › 语音转文本
        SettingsSearchEntry("本地识别", speech, SettingsRoutes.SpeechToText, "本地 离线 模型 识别方式 在线 无需网络"),
        SettingsSearchEntry("屏蔽外放音", speech, SettingsRoutes.SpeechToText, "屏蔽 外放 压低音量 暂停播放 不屏蔽 静音 音乐 视频 媒体 声音 其他声音 音频焦点 录音 语音", section = "语音输入"),
        SettingsSearchEntry("开车连续语音", speech, SettingsRoutes.SpeechToText, "开车 驾驶 免手 连续 自动 接着听 悬浮条 浮窗 发送 语音", section = "语音输入"),
        SettingsSearchEntry("隔空输入", speech, SettingsRoutes.SpeechToText, "隔空输入 电脑 Windows 发到电脑 手机说话 电脑打字 远程 光标 微信", section = "语音输入"),
        SettingsSearchEntry("保存语音调优录音", speech, SettingsRoutes.SpeechToText, "录音 保存 调优 回放 分析", section = "本地识别"),
        SettingsSearchEntry("X-ASR", speech, SettingsRoutes.SpeechToText, "X-ASR XASR 中文 定稿 整句 准确 错字 模型 下载 语音", section = "中文定稿"),
        SettingsSearchEntry("整句定稿", speech, SettingsRoutes.SpeechToText, "整句 定稿 校正 断句 分段 准确 SenseVoice X-ASR 说完", section = "二次校正"),
        SettingsSearchEntry("自动加标点", speech, SettingsRoutes.SpeechToText, "标点 逗号 句号 问号 感叹号 顿号 分号 自动标点 去掉标点 不加标点 部分标点 只要逗号 不要句号 末尾标点 句末标点 最后不带标点 空格 在线接口 语音", section = "语音标点"),
        SettingsSearchEntry("数字转阿拉伯数字", speech, SettingsRoutes.SpeechToText, "数字 阿拉伯数字 中文数字 大写数字 小写数字 ITN 金额 年份 电话 百分比 小数 时间 语音", section = "数字"),
        SettingsSearchEntry("语音纠错", speech, SettingsRoutes.SpeechToText, "纠错 错词 正词 替换 同音字 识别错 认错 改词 映射 纠错词库 语音", section = "纠错"),
        // 字幕是插件（11-05）：设置在字幕插件那一页；没装时搜索结果换成去插件市场（[withCaptionPluginState]）。
        SettingsSearchEntry("字幕", captionPage, CAPTION_PLUGIN_TARGET, "字幕 听障 老人 对话 实时字幕 插件"),
        SettingsSearchEntry("桌面显示“字幕”图标", captionPage, CAPTION_PLUGIN_TARGET, "字幕 桌面图标 快捷方式"),
        SettingsSearchEntry("后台浮窗", captionPage, CAPTION_PLUGIN_TARGET, "字幕 浮窗 后台 悬浮 其他应用"),
        SettingsSearchEntry("自动保存字幕", captionPage, CAPTION_PLUGIN_TARGET, "字幕 保存 文件 记录 下载"),
        SettingsSearchEntry("字幕只保留 3 天", captionPage, CAPTION_PLUGIN_TARGET, "字幕 记录 删除 三天 保留"),
        SettingsSearchEntry("有人叫我名字时振动", captionPage, CAPTION_PLUGIN_TARGET, "字幕 名字 振动 提醒 叫我"),
        SettingsSearchEntry("在线语音", speech, SettingsRoutes.SpeechToText, "在线 插件 api 密钥 接口 联网 openai whisper 在线语音接口", section = "在线语音"),
        // 智能 › 手写
        SettingsSearchEntry("已安装的手写模型", handwriting, SettingsRoutes.Handwriting, "手写模型 公式 个人手写 下载 切换 删除"),
        SettingsSearchEntry("手写停顿识别", handwriting, SettingsRoutes.Handwriting, "手写 停顿 识别 延迟 时长 秒"),
        SettingsSearchEntry("手写边写边识别", handwriting, SettingsRoutes.Handwriting, "手写 边写边识别 提前 延迟 慢 等待 快"),
        SettingsSearchEntry("手写公式两边的标记", handwriting, SettingsRoutes.Handwriting, "公式 latex 美元 数学"),
        SettingsSearchEntry("手写训练", handwriting, SettingsRoutes.HandwritingTraining, "手写 训练 训练模式 样本 假名 字母 公式 贡献 上传 分享 采集 数据采集 笔迹 标注 自己的文字", anchor = null),
        // 自用包里贡献题并进「手写训练」的题库菜单，搜索只留上面那条
        SettingsSearchEntry("贡献手写样本", handwriting, SettingsRoutes.HandwritingContribute, "手写 贡献 样本 上传 分享 训练 改进 认不出", anchor = null),
        // 系统 › 剪贴板与同步
        SettingsSearchEntry("连接状态", clipboard, SettingsRoutes.ClipboardSync, "扫码连接电脑 连接电脑 Windows 配对 连接码 二维码 互联"),
        SettingsSearchEntry("同步剪贴板", clipboard, SettingsRoutes.ClipboardSync, "同步 剪贴板 电脑 Windows 我的设备", section = "连接状态"),
        SettingsSearchEntry("让其他手机或平板连接本机", clipboard, SettingsRoutes.ClipboardSync, "手机 平板 互相同步 二维码 允许其他设备连接"),
        SettingsSearchEntry("新短信验证码自动复制", clipboard, SettingsRoutes.ClipboardSync, "验证码 短信 自动复制 code"),
        SettingsSearchEntry("验证码发到其他设备", clipboard, SettingsRoutes.ClipboardSync, "验证码 短信 电脑 平板 同步 发送 code"),
        SettingsSearchEntry("记录密码等敏感内容", clipboard, SettingsRoutes.ClipboardSync, "密码 敏感 银行卡 密码管理器 剪贴板 password"),
        page("剪贴板面板", clipboard, SettingsRoutes.ClipboardPanel, "剪贴板 菜单 更多 设置 面板"),
        SettingsSearchEntry("上屏后自动关闭剪贴板", clipboardPanel, SettingsRoutes.ClipboardPanel, "剪贴板 关闭 粘贴 连续贴"),
        SettingsSearchEntry("卡片显示行数", clipboardPanel, SettingsRoutes.ClipboardPanel,
            "剪贴板 卡片 行数 几行 显示 记录 折叠 最多显示 剪贴板卡片最多显示 卡片最多显示"),
        SettingsSearchEntry("最近截图", clipboardPanel, SettingsRoutes.ClipboardPanel, "剪贴板 截图 截屏 相册 图片 照片"),
        SettingsSearchEntry("剪贴板数量上限", clipboardPanel, SettingsRoutes.ClipboardPanel,
            "剪贴板 条数 数量 最多 上限 保存 历史记录 不限 存文档", anchor = "数量上限", section = "历史记录"),
        SettingsSearchEntry("剪贴板保留时间", clipboardPanel, SettingsRoutes.ClipboardPanel,
            "剪贴板 定期清理 自动清理 自动删除 过期 天数 保留 历史记录", anchor = "保留时间", section = "历史记录"),
        SettingsSearchEntry("导出剪贴板", clipboard, SettingsRoutes.ClipboardSync, "导出 剪贴板 记录 固定内容 备份"),
        SettingsSearchEntry("导入剪贴板", clipboard, SettingsRoutes.ClipboardSync, "导入 剪贴板 记录 固定内容 分组 恢复 换机"),
        SettingsSearchEntry("剪贴板分组", clipboardPanel, SettingsRoutes.ClipboardGroups,
            "分组 常用语 自定义分组 新建分组 分类 地址 图片 链接 电话 邮箱 话术 固定内容", anchor = null),
        page("插件市场", "$system › 扩展 › 插件", SettingsRoutes.MarketPlugins, "插件商店 下载 安装 API AI 翻译 帮写 回复 改写 润色 在线语音"),
        page("已安装的插件", "$system › 扩展 › 插件", SettingsRoutes.Plugins, "插件 已安装 插件管理 文件导入 xipk"),
        page("已安装的模型", "$system › 扩展 › 模型", SettingsRoutes.ModelLocal, "模型 识字 语音 手写 本地 下载 模型管理"),
        // 轻量版第一次用时下载，打开下载页；完整版内置在安装包里，打开已安装的模型。
        page("拍照识别模型", "$system › 扩展 › 模型",
            if (com.kingzcheung.xime.BuildConfig.BUNDLED_MODELS) SettingsRoutes.ModelLocal
            else "model_market_detail/${com.kingzcheung.xime.model.BuiltinModelCatalog.OCR_MODEL}",
            "拍照 识字 拍照识字 OCR 扫描 文字识别 图片转文字 相机 下载"),
        // 系统 › 备份与恢复
        SettingsSearchEntry("包含个人词库与学习数据", backup, SettingsRoutes.Backup, "个人词库 学习数据 自造词 调频 隐私"),
        SettingsSearchEntry("导出备份", backup, SettingsRoutes.Backup, "导出 备份 zip 换机", anchor = "操作"),
        SettingsSearchEntry("从备份恢复", backup, SettingsRoutes.Backup, "恢复 导入 还原 备份文件", anchor = "操作"),
        // 系统 › 性能与省电
        SettingsSearchEntry("性能档位", performance, SettingsRoutes.Performance, "档位 自动 推荐 高配 中配 低配 自定义 省电 配置高低 分数 当前位置 刻度", anchor = "档位"),
        SettingsSearchEntry("语音二次校正空闲后释放", performance, SettingsRoutes.Performance, "SenseVoice X-ASR 释放 空闲 内存 语音"),
        SettingsSearchEntry("语音模型空闲后全部释放", performance, SettingsRoutes.Performance, "Paraformer 释放 常驻 空闲 内存 语音 永不"),
        // 系统 › 关于 CyIME
        SettingsSearchEntry("使用文档", about, SettingsRoutes.About, "说明 帮助 文档 教程 用法"),
        SettingsSearchEntry("检查更新", about, SettingsRoutes.About, "更新 升级 新版本 最新版本 下载 安装 版本号"),
        SettingsSearchEntry("自动检查更新", about, SettingsRoutes.About, "自动 更新 升级 新版本 提醒"),
        page("隐私策略", about, SettingsRoutes.Privacy, "隐私 数据 联网 收集"),
        page("鸣谢", about, SettingsRoutes.Credits, "致谢 感谢 鸣谢 上游 Xime 雾凇 参考 来源 作者 credits"),
        page("开源许可证", about, SettingsRoutes.Licenses, "许可 开源 license 第三方"),
        page("日志查看器", about, SettingsRoutes.LogViewer, "日志 log 崩溃 错误"),
        page("存储空间", about, SettingsRoutes.StorageSpace, "存储 空间 占用 清理 缓存 容量 内存 运行内存 RAM 释放 安装包 体积"),
        // 入口在首页最上面一行；搜索路径归到「智能」，和用户学习数据放在一组。
        page("打字统计", smart, SettingsRoutes.TypingStats, "统计 字数 打字速度 码长 纠错 按错 首选率 称号 输入统计"),
        page("英语生词本", smart, SettingsRoutes.VocabularyBook, "生词 单词 英语 英文 学习 背单词 词汇 等级 CEFR 释义 青简"),
    ).filter {
        // Developer-only tools (handwriting training, speech tuning recordings, raw Rime file manager)
        // are hidden in public, elder and friend packages.
        (it.target !in setOf(SettingsRoutes.HandwritingTraining, SettingsRoutes.RimeFileBrowser) &&
            it.title != "保存语音调优录音") || com.kingzcheung.xime.BuildConfig.DEV_TOOLS || com.kingzcheung.xime.BuildConfig.DEBUG
    }.filter {
        it.target != SettingsRoutes.HandwritingContribute ||
            !(com.kingzcheung.xime.BuildConfig.DEV_TOOLS || com.kingzcheung.xime.BuildConfig.DEBUG)
    }.filter {
        // The elder package has no update entry (AppUpdater.supported).
        com.kingzcheung.xime.update.AppUpdater.supported || it.title !in setOf("检查更新", "自动检查更新")
    }
}

/**
 * 同一个意思的不同说法，以及语音输入常写错的同音词（拳角＝全角、扶窗＝浮窗）：查询里出现其中一个，
 * 也按组里其他说法去找。只放意思真的相同或几乎相同的词，免得什么都搜得到。
 */
private val SearchSynonyms: List<List<String>> = listOf(
    listOf("振动", "震动", "震感", "触感", "触觉"),
    listOf("按键音", "按键声", "键盘音", "键盘声", "打字声", "打字音"),
    listOf("声音", "音效"),
    listOf("字号", "字体大小", "文字大小", "字大小"),
    listOf("主题", "皮肤"),
    listOf("配色", "颜色"),
    listOf("深色", "暗色", "夜间", "暗黑", "黑暗"),
    listOf("浅色", "亮色", "日间"),
    listOf("玻璃", "毛玻璃", "磨砂"),
    listOf("透明", "半透明"),
    listOf("剪贴板", "剪切板", "粘贴板", "剪帖板"),
    listOf("候选", "备选", "选词"),
    listOf("九键", "9键", "t9"),
    listOf("26键", "全键盘", "qwerty"),
    listOf("14键", "十四键"),
    listOf("全角", "拳角", "全脚"),
    listOf("半角", "板角", "半脚"),
    listOf("浮窗", "浮创", "扶窗", "悬浮窗", "浮条"),
    listOf("语音", "说话", "麦克风", "话筒"),
    listOf("光标", "游标"),
    listOf("长按", "按住"),
    listOf("拂动", "滑动", "划动"),
    listOf("词库", "词典", "字典"),
    listOf("学习", "记忆", "调频"),
    listOf("省电", "耗电", "电量", "续航"),
    listOf("卡顿", "掉帧", "不流畅", "性能"),
    listOf("电脑", "windows", "pc"),
    listOf("物理键盘", "实体键盘", "外接键盘", "蓝牙键盘", "硬键盘"),
    listOf("恢复", "还原"),
    listOf("图标", "icon"),
    listOf("分词", "隔音符", "分隔符", "撇号"),
    listOf("滑行", "划词", "swype", "glide"),
    listOf("模糊音", "模糊拼音", "近音"),
    // B站评论里的说法（11-01）：「双排」是双拼的口误。
    listOf("双拼", "双排"),
    listOf("角标", "小字", "上下角标"),
    listOf("繁体", "正体"),
    listOf("生僻字", "罕用字", "冷僻字"),
    listOf("联想", "预测"),
    listOf("拍照识别", "拍照识字", "ocr", "文字识别", "扫描"),
    listOf("插件", "扩展", "plugin"),
    listOf("商店", "市场", "store"),
    listOf("表情", "emoji", "颜文字"),
    // 语音输入常见的同音错字
    listOf("按键", "案件"),
    listOf("注音", "初音"),
).map { group -> group.map(::normalizeSearchText) }

/**
 * 繁体字转简体，只收设置页文字里用到的字（由设置页源码的汉字逐字转繁体后生成）：
 * 开了「繁体输出」或习惯繁体的用户搜“繁體”“鍵盤”也能找到简体写的设置。
 */
private const val SEARCH_TRADITIONAL = "萬與東兩個臨為義習書產僅從倉價優會傳體側儲兒兌關內冊寫沖決況凈準減湊幾擊劃則剛創刪別務動勢區協單歷壓雙發變疊臺號嗎聽啟響圍國圖圓場壞塊聲處備復夠頭夾學實審寬對尋導將爾層屬帶幀幫廣庫應廢開棄張彈強歸當錄徑憶態總懸懶戶執擴掃擾搶護報擬撥擇掛擋擠換據攜數斷無舊時顯暫機權條來構標棧欄樣檔檢橫氣漢沒潔淺測瀏濃漸灣潰滾滿濾燈靈點熱爺狀獨環現電畫暢監蓋盤著碼礎確礙離種積稱窩豎筆簽簡類粵緊糾紅約級純縱紙紋線練組細終經綁結給絡統繼緒續維綠緩編緣縫縮網翹聯膚腦腳節蘋薦獲藍虛雖補裝見觀規視覽覺觸計認讓訓議記許設訪證識訴詞譯試話詢該詳語誤說請諾讀誰調謹負責敗賬質貼費資賴車軌轉輪軟軸輕載較輔輯輸邊達遷過運還這進遠連遲跡適選遞鄰釋針鐘鑰鈕鋪鏈銷鎖錯鍵鏤長門閉問閑間際陸險隨隱霧靜韓韻頁頂項順須頓預領頻題顏風飾饋馬驅駐驗鶴麥齊後裡裏麼於隻製鬆範髮乾幹遊檯颱衝週佈鍾誌瞭"
private const val SEARCH_SIMPLIFIED = "万与东两个临为义习书产仅从仓价优会传体侧储儿兑关内册写冲决况净准减凑几击划则刚创删别务动势区协单历压双发变叠台号吗听启响围国图圆场坏块声处备复够头夹学实审宽对寻导将尔层属带帧帮广库应废开弃张弹强归当录径忆态总悬懒户执扩扫扰抢护报拟拨择挂挡挤换据携数断无旧时显暂机权条来构标栈栏样档检横气汉没洁浅测浏浓渐湾溃滚满滤灯灵点热爷状独环现电画畅监盖盘着码础确碍离种积称窝竖笔签简类粤紧纠红约级纯纵纸纹线练组细终经绑结给络统继绪续维绿缓编缘缝缩网翘联肤脑脚节苹荐获蓝虚虽补装见观规视览觉触计认让训议记许设访证识诉词译试话询该详语误说请诺读谁调谨负责败账质贴费资赖车轨转轮软轴轻载较辅辑输边达迁过运还这进远连迟迹适选递邻释针钟钥钮铺链销锁错键镂长门闭问闲间际陆险随隐雾静韩韵页顶项顺须顿预领频题颜风饰馈马驱驻验鹤麦齐后里里么于只制松范发干干游台台冲周布钟志了"

/** 小写、全角转半角、繁体转简体，去掉空格和标点：查询和索引都先这样处理。 */
internal fun normalizeSearchText(s: String): String = buildString {
    for (raw in s.lowercase()) {
        val t = SEARCH_TRADITIONAL.indexOf(raw)
        val c = if (t >= 0) SEARCH_SIMPLIFIED[t] else if (raw in '！'..'～') (raw.code - 0xFEE0).toChar() else raw
        if (c.isWhitespace() || c in "，。、·›：:；;（）()「」“”\"'+-_/．.,") continue
        append(c)
    }
}

/** 一个查询词连同同义说法：词里含组里某个说法，就把它换成同组其他说法各算一个变体（第一个是原词）。 */
private fun searchVariants(term: String): List<String> {
    val out = linkedSetOf(term)
    for (group in SearchSynonyms) for (word in group) {
        if (word.isEmpty() || word !in term) continue
        for (alt in group) if (alt != word) out += term.replace(word, alt)
    }
    return out.toList()
}

private const val FUZZY_MIN_COVERAGE = 0.6

/**
 * 一个查询词对一条索引的得分，0 表示不匹配：标题里有 > 说法里有 > 路径里有；同义说法打九折。
 * 都没有时按相邻两字逐段找，覆盖六成以上算弱匹配（“按键声音大小”能找到“音量大小”，“候选字号”找到“候选字大小”）。
 */
private fun termScore(term: String, title: String, keywords: String, path: String): Int {
    val variants = searchVariants(term)
    var best = 0
    variants.forEachIndexed { i, v ->
        val s = when {
            title == v -> 130
            title.startsWith(v) -> 115
            v in title -> 100
            v in keywords -> 60
            v in path -> 40
            else -> 0
        }
        best = maxOf(best, if (i == 0) s else s * 9 / 10)
    }
    if (best > 0) return best
    val all = "$title\u0000$keywords\u0000$path"
    var coverage = 0.0
    for (v in variants) {
        if (v.length < 3) continue
        val pieces = v.windowed(2)
        val hit = pieces.count { it in all }
        if (hit >= 2) coverage = maxOf(coverage, hit.toDouble() / pieces.size)
    }
    return if (coverage >= FUZZY_MIN_COVERAGE) (30 * coverage).toInt() else 0
}

/** 按空格分词，每个词都要匹配上；得分高的在前，同分保持索引顺序（整页在前，页内按页面顺序）。 */
internal fun searchSettings(query: String, index: List<SettingsSearchEntry> = SettingsSearchIndex): List<SettingsSearchEntry> {
    val terms = query.trim().split(Regex("\\s+")).map(::normalizeSearchText).filter { it.isNotEmpty() }
    if (terms.isEmpty()) return emptyList()
    return index.mapNotNull { entry ->
        val title = normalizeSearchText(entry.title)
        val keywords = normalizeSearchText(entry.keywords)
        val path = normalizeSearchText(entry.path)
        var total = 0
        for (term in terms) {
            val s = termScore(term, title, keywords, path)
            if (s == 0) return@mapNotNull null
            total += s
        }
        entry to total
    }.sortedByDescending { it.second }.map { it.first }
}

@Composable
internal fun SettingsSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
            BasicTextField(value = query, onValueChange = onQueryChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().testTag("settings-search"))
            if (query.isEmpty()) Text("搜索设置，如“拂动”“长按”“字号”", style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
        if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Default.Close, "清除搜索") }
        else Spacer(Modifier.size(48.dp))
    }
}

@Composable
internal fun SettingsSearchResults(query: String, onOpen: (SettingsSearchEntry) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val results = withCaptionPluginState(searchSettings(query), com.kingzcheung.xime.ui.caption.CaptionPlugin.isActive(context))
    if (results.isEmpty()) {
        Text("没有找到「$query」相关的设置", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        return
    }
    SettingsSection(title = "搜索结果", content = {
        results.take(30).forEachIndexed { index, entry ->
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            Column(Modifier.fillMaxWidth().clickable { SettingsSearchFocus.open(entry); onOpen(entry) }.testTag("settings-search-result:${entry.title}")
                .padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(entry.title, style = MaterialTheme.typography.bodyLarge)
                Text(entry.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    })
}

private fun nowMs() = System.nanoTime() / 1_000_000

/** 页面切换动画（[SettingsPageMotion] 300 ms）结束后再滚动、高亮，用户看得到是哪一项。 */
private const val SEARCH_SETTLE_MS = 360L

/** 超过这个时间还没找到就放弃，免得以后进别的页面时突然亮起来。 */
private const val SEARCH_EXPIRE_MS = 8_000L

/**
 * 点开搜索结果后要定位的那一项。目标页上标了 [settingsSearchAnchor] 的项出现时，
 * 按请求里的先后（先具体那一行，再它所在的组）选一个滚到可见处并渐变高亮一次，然后清掉请求。
 */
internal object SettingsSearchFocus {
    internal class Request(val keys: List<String>, val at: Long)

    var request by mutableStateOf<Request?>(null)
        private set

    private val shown = mutableMapOf<String, Int>()

    fun open(entry: SettingsSearchEntry) {
        val keys = listOfNotNull(entry.anchor, entry.section)
        request = if (keys.isEmpty()) null else Request(keys, nowMs())
    }

    internal fun register(key: String) { shown[key] = (shown[key] ?: 0) + 1 }

    internal fun unregister(key: String) {
        val n = (shown[key] ?: 0) - 1
        if (n > 0) shown[key] = n else shown.remove(key)
    }

    internal fun isLive(r: Request) = request === r && nowMs() - r.at < SEARCH_EXPIRE_MS

    /** 请求里有一项已经出现在页面上（懒加载列表据此停止往下翻）。 */
    internal fun anyShown(r: Request) = r.keys.any { it in shown }

    /** [key] 是不是该亮的那一项：排在它前面的项已在页面上就让给那一项。认领后清掉请求，只亮一次。 */
    internal fun claim(key: String, r: Request): Boolean {
        if (!isLive(r)) return false
        val rank = r.keys.indexOf(key)
        if (rank < 0 || r.keys.take(rank).any { it in shown }) return false
        request = null
        return true
    }
}

/** 设置首页本身不作为定位目标：它和子页面有同名项，切页动画期间首页还在，不能抢先亮。 */
internal val LocalSettingsSearchAnchors = staticCompositionLocalOf { true }

/**
 * 标出设置搜索能定位到的一项，[key] 与索引里的 anchor / section 相同（通常就是这一行的标题）。
 * 被搜索点中时滚到可见处（上下留出空隙，不贴边），再用主题色渐变亮起、停一下、慢慢褪去。
 * 高亮叠在内容上面，有自己底色的卡片也看得见；[bleed] 让内边距里的项把高亮铺到卡片两边。
 */
internal fun Modifier.settingsSearchAnchor(key: String, bleed: Dp = 0.dp, bleedVertical: Dp = 0.dp): Modifier = composed {
    if (!LocalSettingsSearchAnchors.current) return@composed Modifier
    val requester = remember { BringIntoViewRequester() }
    val glow = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    DisposableEffect(key) {
        SettingsSearchFocus.register(key)
        onDispose { SettingsSearchFocus.unregister(key) }
    }
    val request = SettingsSearchFocus.request
    LaunchedEffect(request, key) {
        val r = request ?: return@LaunchedEffect
        if (key !in r.keys) return@LaunchedEffect
        val wait = r.at + SEARCH_SETTLE_MS - nowMs()
        if (wait > 0) delay(wait)
        withFrameNanos { }
        if (!SettingsSearchFocus.claim(key, r)) return@LaunchedEffect
        // 认领后请求清空会让本 effect 重启，动画放到不随它取消的作用域里。
        scope.launch {
            val margin = with(density) { 96.dp.toPx() }
            requester.bringIntoView(Rect(0f, -margin, size.width.toFloat(), size.height + margin))
            glow.animateTo(1f, tween(220))
            delay(650)
            glow.animateTo(0f, tween(1400, easing = LinearOutSlowInEasing))
        }
    }
    val color = MaterialTheme.colorScheme.primary
    Modifier
        .bringIntoViewRequester(requester)
        .onSizeChanged { size = it }
        .drawWithContent {
            drawContent()
            val a = glow.value
            if (a > 0f) {
                val bx = bleed.toPx()
                val by = bleedVertical.toPx()
                drawRoundRect(color.copy(alpha = 0.2f * a), topLeft = Offset(-bx, -by),
                    size = Size(this.size.width + 2 * bx, this.size.height + 2 * by),
                    cornerRadius = CornerRadius(12.dp.toPx()))
            }
        }
        .testTag("settings-search-anchor:$key")
}

/**
 * 懒加载列表页用的 [LazyListState]：要定位的项还没组合出来时往下翻，直到它出现或到底；
 * 出现后由 [settingsSearchAnchor] 自己滚准并高亮。
 */
@Composable
internal fun rememberSettingsSearchListState(): LazyListState = rememberLazyListState().also { SettingsSearchAutoScroll(it) }

/** 已有自己 [LazyListState] 的页面（如可拖动排序的语言列表）直接调这个。 */
@Composable
internal fun SettingsSearchAutoScroll(state: LazyListState) {
    val request = SettingsSearchFocus.request
    LaunchedEffect(request) {
        val r = request ?: return@LaunchedEffect
        withFrameNanos { }
        while (SettingsSearchFocus.isLive(r) && !SettingsSearchFocus.anyShown(r) && state.canScrollForward) {
            state.scrollBy(state.layoutInfo.viewportSize.height * 0.8f)
            withFrameNanos { }
        }
    }
}
