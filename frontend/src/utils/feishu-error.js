// 仅展示 SDK 的数字错误码；原始消息可能包含签名、票据、URL 或其他凭证，
// 因此禁止直接展示或序列化 SDK 返回的原始数据。
export function getFeishuErrorCode(error) {
  for (const field of ['errno', 'errCode', 'errorCode', 'code']) {
    const value = error?.[field]
    if (
      (typeof value === 'number' && Number.isSafeInteger(value)) ||
      (typeof value === 'string' && /^\d{1,10}$/.test(value))
    ) {
      return String(value)
    }
  }
  return null
}

function getInnerErrorCode(error) {
  for (const field of ['errString', 'errMsg', 'errorMessage', 'message']) {
    const value = error?.[field]
    if (typeof value !== 'string') {
      continue
    }
    const match = value
      .slice(0, 4096)
      .match(/(?:error\s*code|code|错误码)\s*[:：=]\s*["']?(-?\d{1,10})(?!\d)/i)
    if (match) {
      return match[1]
    }
  }
  return null
}

export function feishuFailure(stage, error) {
  const code = getFeishuErrorCode(error)
  const innerCode = code === '2700002' ? getInnerErrorCode(error) : null
  const suggestions = {
    103: '当前客户端不支持此授权接口，请升级飞书后重试。',
    333441: '页面签名校验失败，请重新打开应用后重试。',
    333442: '应用票据无效，请检查应用凭证后重试。',
    333447: '尚未配置 H5 可信域名，请在飞书开发者后台添加当前页面域名。',
    333448: '当前页面不在 H5 可信域名内，请核对域名及端口。',
    333449: '当前账号不在应用可用范围内，请检查当前飞书企业及应用版本。',
    2700002:
      '请先核对飞书后台的重定向 URL 是否包含当前页面完整地址（含协议、端口和路径），再检查应用可用范围及是否取消授权。',
  }
  const hint = suggestions[code] || '请检查当前飞书账号的应用可用范围及应用配置后重试。'
  return new Error(
    `${stage}失败${code ? `（飞书错误码 ${code}${innerCode ? `，内层错误码 ${innerCode}` : ''}）` : '（未返回错误码）'}。${hint}`,
  )
}
