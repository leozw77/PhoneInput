# PhoneInputEnhanced Native preview.2 开发说明

## 目标

本阶段只迁移单指点击与拖动手势，不提前混入双指和原生 IME。

## 状态机

`Idle → TapCandidate → SingleMove | PressDrag | DoubleTapDrag`

持久拖动使用 `DragLocked`；两指及以上在本版进入 `Suppressed`，等待所有手指离开后恢复。

## 参数

- 点击/移动判定容差：7px
- 普通按住拖动准备时间：220ms
- 双击第二次拖动准备时间：150ms
- 拖动启动位移：12px
- 双击最大间隔：300ms
- 双击位置距离：36px

上述参数集中在 `GestureConfig.kt`。

## 安全释放

- PressDrag / DoubleTapDrag 抬手：发送 left up。
- 拖动锁定关闭：发送 left up。
- 多指接入：立即取消当前拖动并释放锁定。
- Activity onPause：本地状态清空 + Protocol v2 release。
- WebSocket 最后一个控制连接消失：Windows 端 `releaseAllButtons()` 兜底。

## 网络层

preview.2 没有新增 Protocol v2 消息类型，只在 Android 封装层公开既有：

`button {button:left, down:true|false}`

因此 preview.1 Windows v2 Host 从协议能力上已经兼容 preview.2；正式发布包仅同步版本号。
