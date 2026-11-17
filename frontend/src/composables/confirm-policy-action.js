import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'
/** 将申请确认框结果转换为表单动作所需的布尔值，按钮文案由业务动作提供。 */
export async function confirmPolicyAction(
  message,
  { confirmButtonText = '确定', cancelButtonText = '取消' } = {},
) {
  try {
    await ElMessageBox.confirm(message, '确认操作', {
      type: 'warning',
      confirmButtonText,
      cancelButtonText,
      // 手动修改 Hash 地址也会进入路由确认，不能由同一次 hashchange 关闭弹窗。
      closeOnHashChange: false,
    })
    return true
  } catch {
    return false
  }
}
