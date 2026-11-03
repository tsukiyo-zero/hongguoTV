果映 TV 0.1.17 更新说明

- 推荐会话失效时重置游标重试，避免持续 110
- passport 请求不回发 Cookie，避免 sms_login 被判 2046
- 短信登录改用设备表固定号且不携带注册 Cookie
- passport 登录改用 security.snssdk.com 且 query 不带设备号
- 手机号登录加区号选择器（可搜索）
- 手机号登录改为单面板输入，验证码按钮带倒计时
- 验证码登录改用 sms_login 端点并让 error_code 1 继续换路
- 手机号短信登录改走 App passport 通道

支持 Android 6.0 及以上；保持新包名与原签名，同包名旧版可覆盖更新并保留数据。
