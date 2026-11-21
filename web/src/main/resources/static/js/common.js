/* ============================================================
   common.js —— 控制台公共基础设施
   API 客户端 / 格式化 / 图表 / 通用组件
   依赖：console.css
   ============================================================ */
(function (global) {
  'use strict';

  /* ---------------- API 客户端 ---------------- */
  // 页面统一从网关进入：从 18080 打开时走同源相对路径（无跨域）；
  // 从 file:// 预览面板或直连 8080 打开时，回指网关地址（网关已放开开发期 CORS）。
  // 注意：8080 上的业务 Controller 已摘除，不能再用它作 API 基址。
  const API_BASE = location.port === '18080' ? '' : 'http://127.0.0.1:18080';

  function getToken() { return localStorage.getItem('fp_token'); }

  /**
   * 统一请求封装：自动附加 Bearer 令牌与 X-Operator-Id 审计头。
   * 后端统一响应体：{ code, message, data, traceId }
   */
  async function api(path, options) {
    options = options || {};
    const token = getToken();
    const resp = await fetch(API_BASE + path, {
      method: options.method || 'GET',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer ' + token,
        'X-Operator-Id': localStorage.getItem('fp_username') || 'unknown',
        ...(options.headers || {})
      },
      body: options.body ? JSON.stringify(options.body) : undefined
    });

    if (resp.status === 401) {
      const err = new Error('登录已失效，请重新登录');
      err.unauthorized = true;
      throw err;
    }

    const body = await resp.json().catch(function () { return {}; });
    if (!resp.ok || body.code !== 'OK') {
      const e = new Error(body.message || ('请求失败（HTTP ' + resp.status + '）'));
      e.code = body.code;
      e.traceId = body.traceId;
      e.details = body.details;
      // 403：角色权限不足。单独标记出来，便于调用方给出"请联系管理员"的提示，
      // 而不是笼统的"操作失败"。
      e.forbidden = resp.status === 403;
      throw e;
    }
    return body.data;
  }

  const GET = (p) => api(p);
  const POST = (p, body, headers) => api(p, { method: 'POST', body: body, headers: headers });
  const PUT = (p, body) => api(p, { method: 'PUT', body: body });
  const DEL = (p) => api(p, { method: 'DELETE' });

  /* ---------------- 当前身份（角色感知） ---------------- */

  /**
   * 解析 JWT 载荷（Base64URL 解码）。
   * 注意：载荷只是编码，不是加密——任何人都能解出来看。
   * 前端解析它只是为了知道"我是谁、能看见哪些按钮"，
   * 真正的权限判定在后端（@RequireAdmin 拦截器）。
   */
  function parseClaims(token) {
    if (!token) return null;
    try {
      const p = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
      return JSON.parse(decodeURIComponent(atob(p + '='.repeat((4 - p.length % 4) % 4))
        .split('').map(function (c) { return '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2); }).join('')));
    } catch (e) { return null; }
  }

  const auth = {
    /** 令牌载荷（含 sub / role / roles / perms / displayName / exp） */
    claims: function () {
      return parseClaims(getToken()) || {};
    },
    /** 当前主角色：SYSTEM_ADMIN / BUSINESS_OPERATOR 等（UserRole 枚举名） */
    role: function () {
      const c = auth.claims();
      return c.role || localStorage.getItem('fp_role') || 'READONLY_USER';
    },
    /**
     * 当前用户持有的全部角色（一人多岗）。
     *
     * <p>令牌载荷里的 roles 数组是权威来源（后端签名保护）；
     * 本地存储只是页面快速读取用。</p>
     */
    roles: function () {
      const c = auth.claims();
      if (Array.isArray(c.roles) && c.roles.length) return c.roles.slice();
      const one = auth.role();
      return one ? [one] : [];
    },
    /** 角色中文名列表（用于展示"兼岗"） */
    roleLabels: function () {
      const c = auth.claims();
      if (Array.isArray(c.roleLabels) && c.roleLabels.length) return c.roleLabels.slice();
      // 兜底：登录时已写入本地存储
      try {
        const saved = JSON.parse(localStorage.getItem('fp_role_labels') || '[]');
        if (Array.isArray(saved) && saved.length) return saved;
      } catch (e) { /* 忽略 */ }
      return [];
    },
    /**
     * 当前用户的有效权限点集合。
     *
     * <p>来自令牌 perms 载荷（后端按用户角色并集计算后签发）。
     * 前端用它决定操作按钮是否显示——但这只是体验优化，
     * 真正的拦截永远在后端 {@code @RequirePermission}。</p>
     */
    permissions: function () {
      const c = auth.claims();
      return Array.isArray(c.perms) ? c.perms.slice() : [];
    },
    /**
     * 是否拥有指定权限点。
     *
     * @param {string} perm 权限点名称，如 'PURCHASE_IMPORT'
     * @returns {boolean}
     */
    can: function (perm) {
      return auth.permissions().indexOf(perm) >= 0;
    },
    /** 是否系统管理员（保留旧调用点） */
    isAdmin: function () {
      const c = auth.claims();
      return c.role === 'SYSTEM_ADMIN' || c.role === 'ADMIN';
    },
    /** 当前登录名 */
    username: function () {
      return auth.claims().sub || localStorage.getItem('fp_username') || '';
    }
  };

  /* ---------------- 格式化 ---------------- */
  const fmt = {
    /** 日元金额：大额自动转「万 / 亿」单位 */
    yen(n) {
      const p = fmt.yenParts(n);
      return p.v === '-' ? '-' : p.v + ' ' + p.u;
    },
    /** 拆成数值与单位两部分（KPI 卡需要分开排版） */
    yenParts(n) {
      if (n === null || n === undefined || n === '') return { v: '-', u: '' };
      const v = Number(n);
      if (isNaN(v)) return { v: '-', u: '' };
      if (Math.abs(v) >= 100000000) return { v: (v / 100000000).toFixed(2), u: '亿日元' };
      if (Math.abs(v) >= 10000) return { v: (v / 10000).toFixed(1), u: '万日元' };
      return { v: v.toLocaleString('zh-CN'), u: '日元' };
    },
    /** 完整金额（带 ¥） */
    yenFull(n) {
      if (n === null || n === undefined || n === '') return '-';
      return '¥' + Number(n).toLocaleString('zh-CN');
    },
    pct(n, digits) {
      if (n === null || n === undefined) return '-';
      return (Number(n) * 100).toFixed(digits === undefined ? 1 : digits) + '%';
    },
    rate(n) {
      if (n === null || n === undefined) return '-';
      return (Number(n) * 100).toFixed(2) + '%';
    },
    num(n) {
      if (n === null || n === undefined) return '-';
      return Number(n).toLocaleString('zh-CN');
    },
    date(s) {
      if (!s) return '-';
      return String(s).substring(0, 10);
    },
    datetime(s) {
      if (!s) return '-';
      return String(s).replace('T', ' ').substring(0, 19);
    },
    /** 相对天数的人话描述 */
    days(n) {
      if (n === null || n === undefined) return '-';
      return n + ' 天';
    }
  };

  /* ---------------- 状态映射 ---------------- */
  const STATUS = {
    // 车辆状态（对应 domain VehicleStatus 枚举）
    IN_STOCK: { text: '在库', cls: 'info' },
    CREATED: { text: '已建账', cls: 'neutral' },
    PENDING_ADVANCE: { text: '待放款', cls: 'warn' },
    FINANCED: { text: '在途', cls: 'info' },
    SOLD_PENDING: { text: '已售待结算', cls: 'warn' },
    SETTLED: { text: '已结清', cls: 'ok' },
    // 已赎回：本金结清、担保解除，但车还在经销商场地，可再次申请垫款。
    // 与"已结清"必须区分：结清的车已售出、不会再回来。
    REDEEMED: { text: '已赎回', cls: 'ok' },
    OVERDUE: { text: '已逾期', cls: 'danger' },
    DISPOSING: { text: '处置中', cls: 'danger' },
    CLOSED: { text: '已关闭', cls: 'neutral' },
    REPOSSESSED: { text: '已收回', cls: 'danger' },
    DISPOSED: { text: '已处置', cls: 'neutral' },
    // 额度状态
    ACTIVE: { text: '生效中', cls: 'ok' },
    FROZEN: { text: '已冻结', cls: 'danger' },
    EXPIRED: { text: '已到期', cls: 'neutral' },
    // 通用
    SUCCESS: { text: '成功', cls: 'ok' },
    FAILED: { text: '失败', cls: 'danger' },
    // 垫款已作废：钱一分未付（经销商撤销 / 风控驳回 / 超时未放款）。
    // 记录保留在台账里，不占"一车一笔垫款"名额，可随时重新申请。
    VOIDED: { text: '已作废', cls: 'neutral' },
    PROCESSING: { text: '放款处理中', cls: 'warn' },
    REVERSED: { text: '已冲正', cls: 'neutral' },
    OPEN: { text: '未处理', cls: 'danger' },
    CLOSED_ALERT: { text: '已关闭', cls: 'ok' },
    HIGH: { text: '高', cls: 'danger' },
    MEDIUM: { text: '中', cls: 'warn' },
    LOW: { text: '低', cls: 'ok' },
    // 评级
    A: { text: 'A 级', cls: 'ok' },
    B: { text: 'B 级', cls: 'info' },
    C: { text: 'C 级', cls: 'warn' },
    D: { text: 'D 级', cls: 'danger' },
    // 担保方式（SecurityType）
    RETENTION: { text: '所有权保留', cls: 'info' },
    ASSIGNMENT: { text: '让与担保', cls: 'info' },
    MANUFACTURER_REPURCHASE: { text: '厂家回购', cls: 'info' },
    OTHER: { text: '其他', cls: 'neutral' },
    NONE: { text: '无担保', cls: 'neutral' },
    // 担保状态（SecurityStatus）
    PENDING: { text: '待登记', cls: 'warn' },
    REGISTERED: { text: '已登记', cls: 'ok' },
    RELEASED: { text: '已解除', cls: 'neutral' },
    // 经销商状态（DealerStatus）
    // 注意：经销商的 ACTIVE 语义是"正常经营"，与额度的 ACTIVE（生效中）不同，
    // 所以单独一套映射，不能复用上面的通用表。
    SUSPENDED: { text: '暂停合作', cls: 'warn' },
    TERMINATED: { text: '终止合作', cls: 'neutral' },

    /* ---- ADR-007：采购订单 / 上游 / 账户 ---- */
    // 采购订单状态
    DRAFT: { text: '草稿', cls: 'neutral' },
    CONFIRMED: { text: '已确认', cls: 'ok' },
    DISBURSED: { text: '已放款', cls: 'info' },
    SETTLED: { text: '已结清', cls: 'neutral' },
    CANCELLED: { text: '已取消', cls: 'neutral' },
    // 打款模式（由 supplier_type 派生，不可人工覆盖）
    DIRECT_PAY: { text: '代付上游', cls: 'info' },
    SELF_PAY: { text: '回款经销商', cls: 'warn' },
    // 上游类型
    MAKER_SALES_CO: { text: '制造商销售公司', cls: 'info' },
    REGIONAL_AGENCY: { text: '区域总代理', cls: 'info' },
    WHOLESALER: { text: '批发公司', cls: 'info' },
    AUCTION: { text: '拍卖行', cls: 'warn' },
    USED_WHOLESALER: { text: '中古车批发商', cls: 'warn' },
    DEALER_SELF: { text: '经销商自身', cls: 'warn' },
    // 担保/回购能力
    STRONG: { text: '强（厂商回购）', cls: 'ok' },
    LIMITED: { text: '有限', cls: 'warn' },
    // 收款账户状态
    PENDING_REVIEW: { text: '待复核', cls: 'warn' },
    FROZEN: { text: '已冻结', cls: 'danger' },
    // 导入通道
    API: { text: 'API 直连', cls: 'info' },
    EXCEL: { text: 'Excel 导入', cls: 'info' },
    // 导入批次状态
    PREVIEWED: { text: '已预校验', cls: 'warn' },
    COMMITTED: { text: '已落库', cls: 'ok' },
    REJECTED: { text: '已拒绝', cls: 'danger' }
  };

  /**
   * 上游类型选项（新增上游表单用）。
   *
   * <p>注意：供应商类型决定打款模式，因此选项上标注了打款模式，
   * 让操作员在选择时就看到"钱会打给谁"。</p>
   */
  const SUPPLIER_TYPE_OPTIONS = [
    { value: 'MAKER_SALES_CO', label: '制造商销售公司（代付上游）' },
    { value: 'REGIONAL_AGENCY', label: '区域总代理（代付上游）' },
    { value: 'WHOLESALER', label: '批发公司（代付上游）' },
    { value: 'AUCTION', label: '拍卖行（代付上游）' },
    { value: 'USED_WHOLESALER', label: '中古车批发商（代付上游）' },
    { value: 'DEALER_SELF', label: '经销商自身（回款经销商 · 营运资金）' }
  ];

  /**
   * 打款模式徽章。
   *
   * <p>与普通 {@link badge} 分开：DIRECT_PAY/SELF_PAY 是派生值，
   * 需要强调"不可人工覆盖"这个语义（否则用户会想直接改它）。</p>
   *
   * @param mode 打款模式代码
   * @return 徽章 HTML
   */
  function payeeModeBadge(mode) {
    if (!mode) return '<span class="badge neutral">-</span>';
    const m = STATUS[mode];
    if (!m) return '<span class="badge neutral">' + mode + '</span>';
    return '<span class="badge ' + m.cls + '" title="打款模式由供应商类型派生，不可人工覆盖">' + m.text + '</span>';
  }

  /** 经销商状态徽章（ACTIVE 在额度语境是"生效中"，在经销商语境是"正常经营"） */
  function dealerBadge(status) {
    if (!status) return '<span class="badge neutral">-</span>';
    if (status === 'ACTIVE') return '<span class="badge ok">正常经营</span>';
    const m = STATUS[status];
    if (!m) return '<span class="badge neutral">' + status + '</span>';
    return '<span class="badge ' + m.cls + '">' + m.text + '</span>';
  }

  function badge(code) {
    if (!code) return '<span class="badge neutral">-</span>';
    const m = STATUS[code];
    if (!m) return '<span class="badge neutral">' + code + '</span>';
    return '<span class="badge ' + m.cls + '">' + m.text + '</span>';
  }

  /** 告警状态单独映射：CLOSED 在车辆语境是「已关闭」，在告警语境是「已处理」 */
  function alertStatusBadge(status) {
    if (status === 'OPEN') return '<span class="badge danger">未处理</span>';
    if (status === 'CLOSED') return '<span class="badge ok">已处理</span>';
    return badge(status);
  }

  /* ---------------- DOM 工具 ---------------- */
  const $ = (sel, root) => (root || document).querySelector(sel);
  const $$ = (sel, root) => Array.from((root || document).querySelectorAll(sel));

  function el(tag, attrs, children) {
    const node = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (k) {
        if (k === 'class') node.className = attrs[k];
        else if (k === 'html') node.innerHTML = attrs[k];
        else if (k === 'text') node.textContent = attrs[k];
        else if (k.startsWith('on') && typeof attrs[k] === 'function') {
          node.addEventListener(k.substring(2).toLowerCase(), attrs[k]);
        } else if (attrs[k] !== null && attrs[k] !== undefined) {
          node.setAttribute(k, attrs[k]);
        }
      });
    }
    (children || []).forEach(function (c) {
      if (typeof c === 'string') node.appendChild(document.createTextNode(c));
      else if (c) node.appendChild(c);
    });
    return node;
  }

  /* ---------------- Toast ---------------- */
  function toast(msg, type) {
    let wrap = $('.toast-wrap');
    if (!wrap) {
      wrap = el('div', { class: 'toast-wrap' });
      document.body.appendChild(wrap);
    }
    const t = el('div', { class: 'toast ' + (type || ''), text: msg });
    wrap.appendChild(t);
    setTimeout(function () {
      t.style.opacity = '0';
      t.style.transition = 'opacity .25s';
      setTimeout(function () { t.remove(); }, 260);
    }, 3200);
  }

  /* ---------------- 弹窗 ---------------- */
  function modal(opts) {
    const mask = el('div', { class: 'modal-mask' });
    const box = el('div', { class: 'modal' });
    box.appendChild(el('header', {}, [
      el('h3', { text: opts.title || '操作' }),
      el('span', { class: 'x', text: '×', onclick: close })
    ]));
    const body = el('div', { class: 'body' });
    if (typeof opts.content === 'string') body.innerHTML = opts.content;
    else if (opts.content) body.appendChild(opts.content);
    box.appendChild(body);

    const footer = el('footer');
    footer.appendChild(el('button', {
      class: 'btn gray', text: '取消', onclick: close
    }));
    const okBtn = el('button', {
      class: 'btn ' + (opts.danger ? 'danger' : ''),
      text: opts.okText || '确定',
      onclick: function () {
        if (!opts.onOk) return close();
        let r = opts.onOk(body);
        // onOk 可能是 async：返回的是 Promise，必须等它 settle 再决定是否关闭。
        // 否则 Promise !== false 恒成立，失败时弹窗也会被关掉（用户填的内容全丢）。
        if (r && typeof r.then === 'function') {
          okBtn.disabled = true;
          const original = okBtn.textContent;
          okBtn.textContent = '处理中…';
          r.then(function (v) {
            okBtn.disabled = false;
            okBtn.textContent = original;
            if (v !== false) close();
          }, function () {
            okBtn.disabled = false;
            okBtn.textContent = original;
          });
          return;
        }
        if (r !== false) close();
      }
    });
    footer.appendChild(okBtn);
    box.appendChild(footer);
    mask.appendChild(box);
    mask.addEventListener('click', function (e) { if (e.target === mask) close(); });
    document.body.appendChild(mask);
    mask.classList.add('open');

    function close() { mask.remove(); }
    return { close: close, body: body };
  }

  /** 表单弹窗：fields = [{key,label,type,value,options,hint,required}] */
  function formModal(opts) {
    const wrap = el('div');
    const inputs = {};
    (opts.fields || []).forEach(function (f) {
      const field = el('div', { class: 'field' });
      field.appendChild(el('label', { text: f.label + (f.required ? ' *' : '') }));
      let input;
      if (f.type === 'select') {
        input = el('select', { class: 'input' });
        (f.options || []).forEach(function (o) {
          const op = el('option', { value: o.value, text: o.label });
          if (String(o.value) === String(f.value)) op.selected = true;
          input.appendChild(op);
        });
      } else if (f.type === 'textarea') {
        input = el('textarea', { class: 'input', rows: '3', style: 'width:100%' });
        input.value = f.value || '';
      } else {
        input = el('input', {
          class: 'input', type: f.type || 'text',
          value: f.value === null || f.value === undefined ? '' : f.value,
          placeholder: f.placeholder || ''
        });
        input.style.width = '100%';
      }
      inputs[f.key] = input;
      field.appendChild(input);
      if (f.hint) field.appendChild(el('div', { class: 'hint', html: f.hint }));
      wrap.appendChild(field);
    });

    return modal({
      title: opts.title,
      content: wrap,
      okText: opts.okText || '提交',
      danger: opts.danger,
      onOk: function () {
        const values = {};
        // 注意：不能用 forEach + return false 来做必填校验——
        // forEach 回调里的 return 只跳出「本次迭代」，后续字段照样被处理，
        // 最终仍会提交。必须用标志位 + 循环中断。
        const fields = opts.fields || [];
        for (let i = 0; i < fields.length; i++) {
          const f = fields[i];
          let v = inputs[f.key].value;
          if (f.type === 'number') v = v === '' ? null : Number(v);
          if (f.required && (v === '' || v === null || v === undefined)) {
            toast('请填写「' + f.label + '」', 'warn');
            inputs[f.key].focus();
            return false;
          }
          values[f.key] = v;
        }
        return opts.onOk(values);
      }
    });
  }

  /* ---------------- 抽屉（支持叠层） ---------------- */

  /**
   * 右侧抽屉。
   *
   * <p><b>为什么支持叠层</b>：从"经销商名下车辆"抽屉里点某台车的"详情"，
   * 如果抽屉是单例，详情会直接顶掉列表，看完就找不回上级了。
   * 这里每次调用都新建一对遮罩+面板并叠在上层（z-index 递增），
   * 关闭只移除自己那层，天然形成"面包屑式"的返回体验。</p>
   */
  let drawerSeq = 0;

  function drawer(title, contentHtml, opts) {
    opts = opts || {};
    const seq = ++drawerSeq;
    const z = 90 + seq * 2;

    const mask = el('div', { class: 'drawer-mask' });
    mask.style.zIndex = String(z);
    // wide = 宽抽屉（880px），给"名下车辆"这类多列明细表用；
    // 默认仍是 520px 的窄抽屉，保持单台车辆详情的紧凑感。
    const box = el('div', { class: 'drawer' + (opts.wide ? ' wide' : '') });
    box.style.zIndex = String(z + 1);

    const head = el('header', {}, [
      el('h3', { text: title }),
      el('span', { class: 'x', text: '×', style: 'font-size:18px;cursor:pointer;color:#8a94a6', onclick: closeDrawer })
    ]);
    const body = el('div', { class: 'body', html: contentHtml });
    box.appendChild(head);
    box.appendChild(body);

    mask.addEventListener('click', closeDrawer);
    document.body.appendChild(mask);
    document.body.appendChild(box);
    mask.classList.add('open');
    box.classList.add('open');

    function closeDrawer() {
      mask.remove();
      box.remove();
    }
    return { body: body, close: closeDrawer };
  }

  /* ---------------- 文件上传（预签名直传三段式） ---------------- */

  /**
   * 附件上传（ADR-007 决策十）。
   *
   * <p><b>三段式</b>：</p>
   * <pre>
   * 1) POST /api/attachments/presign  → 拿上传授权（对象键+类型+长度已锁定）
   * 2) 客户端直传（文件不经过应用）
   * 3) POST /api/attachments/complete → 回读校验后落库
   * </pre>
   *
   * <p>第 2 步<b>不能用 FormData</b>——预签名 URL 的签名参数已绑定
   * Content-Type 与 Content-Length，用 FormData 会额外带 multipart 边界，
   * 签名校验失败。必须按原始字节直传。</p>
   *
   * @param opts.bizType      业务类型（如 PURCHASE_ORDER）
   * @param opts.bizId        业务对象 ID
   * @param opts.file         File 对象
   * @param opts.onProgress   进度回调（可选）
   * @returns {Promise<object>} 附件元数据
   */
  async function uploadAttachment(opts) {
    const file = opts.file;
    if (!file) throw new Error('未选择文件');

    // 1) 申请上传授权
    const auth = await POST('/api/attachments/presign', {
      bizType: opts.bizType,
      bizId: opts.bizId,
      filename: file.name,
      contentType: file.type || 'application/octet-stream',
      contentLength: file.size
    });

    // 2) 直传（原始字节，不走 FormData）
    const resp = await fetch(auth.uploadUrl, {
      method: 'PUT',
      headers: { 'Content-Type': auth.contentType },
      body: file
    });
    if (!resp.ok) {
      throw new Error('上传失败（HTTP ' + resp.status + '）');
    }

    // 3) 完成登记
    return await POST('/api/attachments/complete', {
      objectKey: auth.objectKey,
      filename: file.name,
      contentType: auth.contentType,
      contentLength: auth.contentLength,
      bizType: opts.bizType,
      bizId: opts.bizId
    });
  }

  /**
   * 选择文件并上传（弹出文件选择框）。
   *
   * @param opts 同 {@link uploadAttachment}，另加 onDone（上传完成回调）
   */
  function pickAndUpload(opts) {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = opts.accept || '.pdf,.jpg,.jpeg,.png,.xlsx,.csv';
    input.addEventListener('change', function () {
      const file = input.files && input.files[0];
      if (!file) return;
      const t = toast('正在上传 ' + file.name + '…', '');
      uploadAttachment({
        bizType: opts.bizType, bizId: opts.bizId, file: file
      }).then(function (meta) {
        t.remove();
        toast('附件已上传并登记', 'ok');
        if (opts.onDone) opts.onDone(meta);
      }).catch(function (e) {
        t.remove();
        toast('附件上传失败：' + e.message, 'err');
      });
    });
    input.click();
  }

  /* ---------------- 纯 SVG 图表（无第三方依赖） ---------------- */

  /** 横向条形图：items = [{label, value, color}] */
  function barChart(items, opts) {
    opts = opts || {};
    const max = Math.max.apply(null, items.map(function (i) { return i.value; }).concat([1]));
    const rows = items.map(function (it) {
      const pct = (it.value / max * 100).toFixed(1);
      const color = it.color || 'var(--brand-500)';
      return '<div class="bar-row">' +
        '<div class="lbl" title="' + it.label + '">' + it.label + '</div>' +
        '<div class="track"><div class="fill" style="width:' + pct + '%;background:' + color + '"></div></div>' +
        '<div class="val">' + (opts.fmt ? opts.fmt(it.value) : it.value) + '</div>' +
        '</div>';
    }).join('');
    return '<div class="bars">' + rows + '</div>';
  }

  /** 环形图：segments = [{label, value, color}] */
  function donutChart(segments, centerTitle, centerSub) {
    const total = segments.reduce(function (a, b) { return a + b.value; }, 0);
    const R = 52, C = 2 * Math.PI * R;
    let offset = 0;
    let paths = '';
    segments.forEach(function (s) {
      const len = total ? (s.value / total) * C : 0;
      paths += '<circle cx="70" cy="70" r="' + R + '" fill="none" stroke="' + s.color +
        '" stroke-width="18" stroke-dasharray="' + len.toFixed(2) + ' ' + (C - len).toFixed(2) +
        '" stroke-dashoffset="' + (-offset).toFixed(2) + '" transform="rotate(-90 70 70)"></circle>';
      offset += len;
    });
    const legend = segments.map(function (s) {
      return '<div style="display:flex;align-items:center;gap:7px;font-size:12px;margin-bottom:7px">' +
        '<span style="width:9px;height:9px;border-radius:2px;background:' + s.color + ';flex-shrink:0"></span>' +
        '<span style="color:#5b6678;flex:1">' + s.label + '</span>' +
        '<span style="font-family:Consolas,monospace;color:#1e2634">' + s.value + '</span></div>';
    }).join('');

    const svg = '<svg viewBox="0 0 140 140" style="width:140px;height:140px;flex-shrink:0">' + paths +
      '<text x="70" y="66" text-anchor="middle" style="font-size:20px;font-weight:700;fill:#1a2f5e;font-family:Consolas,monospace">' +
      (centerTitle === undefined ? total : centerTitle) + '</text>' +
      '<text x="70" y="84" text-anchor="middle" style="font-size:10px;fill:#8a94a6">' + (centerSub || '') + '</text></svg>';

    return '<div style="display:flex;align-items:center;gap:20px;flex-wrap:wrap">' +
      '<div style="position:relative">' + svg + '</div>' +
      '<div style="flex:1;min-width:130px">' + legend + '</div></div>';
  }

  /** 折线图：series = [{name, color, points:[{x,y}]}], xLabels = [] */
  function lineChart(series, xLabels) {
    const W = 560, H = 190, PL = 42, PR = 12, PT = 14, PB = 26;
    const all = series.reduce(function (a, s) { return a.concat(s.points.map(function (p) { return p.y; })); }, []);
    const maxY = Math.max.apply(null, all.concat([1]));
    const n = xLabels.length;
    const stepX = n > 1 ? (W - PL - PR) / (n - 1) : 0;

    let grid = '';
    for (let g = 0; g <= 4; g++) {
      const y = PT + (H - PT - PB) * (g / 4);
      const val = Math.round(maxY * (1 - g / 4));
      grid += '<line x1="' + PL + '" y1="' + y + '" x2="' + (W - PR) + '" y2="' + y +
        '" stroke="#eef2f8" stroke-width="1"></line>' +
        '<text x="' + (PL - 6) + '" y="' + (y + 3) + '" text-anchor="end" style="font-size:9px;fill:#8a94a6">' + val + '</text>';
    }

    let xAxis = '';
    xLabels.forEach(function (lb, i) {
      const x = PL + stepX * i;
      xAxis += '<text x="' + x + '" y="' + (H - 8) + '" text-anchor="middle" style="font-size:9px;fill:#8a94a6">' + lb + '</text>';
    });

    let paths = '';
    series.forEach(function (s) {
      const d = s.points.map(function (p, i) {
        const x = PL + stepX * i;
        const y = PT + (H - PT - PB) * (1 - (p.y / maxY));
        return (i === 0 ? 'M' : 'L') + x.toFixed(1) + ',' + y.toFixed(1);
      }).join(' ');
      paths += '<path d="' + d + '" fill="none" stroke="' + s.color + '" stroke-width="2" stroke-linejoin="round"></path>';
      s.points.forEach(function (p, i) {
        const x = PL + stepX * i;
        const y = PT + (H - PT - PB) * (1 - (p.y / maxY));
        paths += '<circle cx="' + x.toFixed(1) + '" cy="' + y.toFixed(1) + '" r="2.6" fill="#fff" stroke="' + s.color + '" stroke-width="1.6"></circle>';
      });
    });

    const legend = series.map(function (s) {
      return '<span style="display:inline-flex;align-items:center;gap:5px;font-size:11px;color:#5b6678;margin-right:14px">' +
        '<span style="width:12px;height:2.5px;background:' + s.color + ';display:inline-block"></span>' + s.name + '</span>';
    }).join('');

    return '<div style="font-size:11px;color:#5b6678;margin-bottom:6px">' + legend + '</div>' +
      '<svg viewBox="0 0 ' + W + ' ' + H + '" style="width:100%;height:auto">' + grid + xAxis + paths + '</svg>';
  }

  /** 额度使用率进度条 */
  function meter(used, limit) {
    const pct = limit > 0 ? (used / limit * 100) : 0;
    const cls = pct >= 90 ? 'danger' : (pct >= 70 ? 'warn' : '');
    return '<div class="meter"><div class="track"><div class="fill ' + cls +
      '" style="width:' + Math.min(100, pct).toFixed(1) + '%"></div></div>' +
      '<span class="pct">' + pct.toFixed(1) + '%</span></div>';
  }

  /** 告警类型（alert_type 枚举，取值与后端 domain 保持一致） */
  const ALERT_TYPE = {
    DISPOSAL_OPEN: '违约处置开单',
    OOT_SUSPECTED: '疑似违约（超库龄）',
    AUDIT_UNEXPECTED: '实物盘点差异',
    AUDIT_MISMATCH: '盘点信息不符',
    INSURANCE_CLAIM: '保险理赔',
    AGING_REDUCTION_DUE: '库龄递减待执行'
  };

  function alertTypeText(type) {
    if (!type) return '-';
    return ALERT_TYPE[type] || type;
  }

  global.FP = global.FP || {};
  global.FP.ALERT_TYPE = ALERT_TYPE;
  global.FP.alertTypeText = alertTypeText;
  global.FP.api = { get: GET, post: POST, put: PUT, del: DEL, raw: api, base: API_BASE };
  global.FP.auth = auth;
  global.FP.fmt = fmt;
  global.FP.badge = badge;
  global.FP.dealerBadge = dealerBadge;
  global.FP.alertStatusBadge = alertStatusBadge;
  global.FP.payeeModeBadge = payeeModeBadge;
  global.FP.SUPPLIER_TYPE_OPTIONS = SUPPLIER_TYPE_OPTIONS;
  global.FP.uploadAttachment = uploadAttachment;
  global.FP.pickAndUpload = pickAndUpload;
  global.FP.STATUS = STATUS;
  global.FP.$ = $;
  global.FP.$$ = $$;
  global.FP.el = el;
  global.FP.toast = toast;
  global.FP.modal = modal;
  global.FP.formModal = formModal;
  global.FP.drawer = drawer;
  global.FP.chart = { bar: barChart, donut: donutChart, line: lineChart, meter: meter };
})(window);
