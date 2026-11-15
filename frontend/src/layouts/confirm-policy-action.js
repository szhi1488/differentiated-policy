import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'
/** 将申请工作台的确认框结果转换为表单动作所需的布尔值。 */
export async function confirmPolicyAction(message) {
  try {
    await ElMessageBox.confirm(message, '确认操作', {
      type: 'warning',
      confirmButtonText: '确认清除',
      cancelButtonText: '保留内容',
      // 手动修改 Hash 地址也会进入路由确认，不能由同一次 hashchange 关闭弹窗。
      closeOnHashChange: false,
    })
    return true
  } catch {
    return false
  }
}
