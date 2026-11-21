/* ============================================================
   purchase-orders.js —— 采购订单 / 上游与账户 视图（ADR-007）
   与 views.js 同层，挂在 FP.views 上。

   核心概念（页面上必须让用户看懂）：
   · 打款模式由供应商类型派生，不可人工覆盖 —— 因此页面上是只读徽章
   · 两条导入通道产出同一份采购订单主单
   · 导入是两段式：preview 只校验、commit 才落库
   · 账户只显示掩码，明文永不回显
   ============================================================ */
(function (global) {
  'use strict';

  const FP = global.FP;
  const { get, post, put } = FP.api;
  const fmt = FP.fmt;
  const badge = FP.badge;
  const $ = FP.$;
  const toast = FP.toast;

  /* ---------------- 局部缓存 ---------------- */
  const cache = {
    orders: null,
    suppliers: null,
    accounts: null,
    dealers: null
  };

  function invalidate() {
    cache.orders = null;
    cache.suppliers = null;
    cache.accounts = null;
    cache.dealers = null;
  }

  async function loadOrders(force) {
    if (cache.orders && !force) return cache.orders;
    cache.orders = await get('/api/purchase-orders');
    return cache.orders;
  }

  async function loadSuppliers(force) {
    if (cache.suppliers && !force) return cache.suppliers;
    cache.suppliers = await get('/api/suppliers');
    return cache.suppliers;
  }

  async function loadAccounts(force) {
    if (cache.accounts && !force) return cache.accounts;
    cache.accounts = await get('/api/bank-accounts');
    return cache.accounts;
  }

  async function loadDealers(force) {
    if (cache.dealers && !force) return cache.dealers;
    cache.dealers = await get('/api/dealers');
    return cache.dealers;
  }

  function dealerName(id) {
    if (id === null || id === undefined) return '—';
    if (!cache.dealers) return '#' + id;
    const d = cache.dealers.filter(function (x) { return x.id === id; })[0];
    return d ? d.name : '#' + id;
  }

  function loading(text) {
    return '<div class="loading"><div class="spinner"></div>' + (text || '加载中…') + '</div>';
  }

  function errBox(msg) {
    return '<div class="empty">加载失败：' + msg + '<br><span style="font-size:11px">请确认后端已启动</span></div>';
  }

  /* ============================================================
     视图：采购订单
     ============================================================ */
  async function purchaseOrders(root) {
    root.innerHTML = loading('正在加载采购订单…');
    try {
      const [orders, dealers] = await Promise.all([loadOrders(), loadDealers()]);
      cache.dealers = dealers;
      // 写操作入口按权限点显隐（真正的拦截在后端 @RequirePermission）
      const canImport = FP.auth.can('PURCHASE_IMPORT');

      const totalAmount = orders.reduce(function (a, o) { return a + Number(o.totalAmount || 0); }, 0);
      const totalVehicles = orders.reduce(function (a, o) { return a + Number(o.vehicleCount || 0); }, 0);
      const directPay = orders.filter(function (o) { return o.payeeMode === 'DIRECT_PAY'; });
      const selfPay = orders.filter(function (o) { return o.payeeMode === 'SELF_PAY'; });

      const rows = orders.map(function (o) {
        return '<tr>' +
          '<td class="mono" style="font-weight:600">' + o.orderNo + '</td>' +
          '<td>' + dealerName(o.dealerId) + '</td>' +
          '<td>' + badge(o.supplierType) + '<div class="muted" style="font-size:11px">' +
          (o.supplierTypeLabel || '') + '</div></td>' +
          '<td>' + FP.payeeModeBadge(o.payeeMode) + '</td>' +
          '<td class="mono muted" style="font-size:11px">' + (o.payeeAccountSnapshot || '—') + '</td>' +
          '<td class="num">' + fmt.yen(o.totalAmount) + '</td>' +
          '<td class="num">' + (o.vehicleCount || 0) + ' 台</td>' +
          '<td>' + badge(o.ingestChannel) + '</td>' +
          '<td>' + badge(o.status) + '</td>' +
          '<td class="muted mono">' + fmt.date(o.purchasedAt) + '</td>' +
          '<td><button class="btn sm ghost" onclick="FP.poViews.orderDetail(\'' +
          o.orderNo.replace(/'/g, "\\'") + '\')">详情</button></td>' +
          '</tr>';
      }).join('') || '<tr><td colspan="11" class="empty">暂无采购订单 —— 请先导入采购清单或由上游系统推送</td></tr>';

      root.innerHTML =
        '<div class="kpi-grid">' +
        kpiYen('采购订单总额', totalAmount, '共 ' + orders.length + ' 张订单', '') +
        kpi('在库车辆数', totalVehicles, '台', '一单多车，订单是锚点', '') +
        kpi('代付上游订单', directPay.length, '张', '直付制造商销售公司 / 总代理 / 批发 / 拍卖', 'ok') +
        kpi('回款经销商订单', selfPay.length, '张', '库存抵押周转 · 营运资金', selfPay.length > 0 ? 'warn' : '') +
        '</div>' +

        '<div class="card"><header><h2>采购订单主单</h2>' +
        '<span class="tag">打款对象由订单决定 · 两条导入通道</span>' +
        '<div class="tools">' +
        (canImport
          ? '<button class="btn sm" onclick="FP.poViews.openImport()">导入采购清单</button>'
          : '<span class="muted" style="font-size:11px">无采购导入权限</span>') +
        '<button class="btn sm ghost" onclick="FP.poViews.downloadTemplate()">下载模板</button>' +
        '</div></header>' +
        '<div class="body flush"><div class="table-wrap"><table class="grid"><thead><tr>' +
        '<th>采购订单号</th><th>经销商</th><th>供应商类型</th><th>打款模式</th><th>收款账户</th>' +
        '<th class="num">采购金额</th><th class="num">车辆</th><th>导入通道</th><th>状态</th><th>采购日</th><th>操作</th>' +
        '</tr></thead><tbody>' + rows + '</tbody></table></div></div></div>';
    } catch (e) {
      root.innerHTML = errBox(e.message);
    }
  }

  function kpi(label, value, unit, foot, cls) {
    return '<div class="kpi ' + (cls || '') + '">' +
      '<div class="label">' + label + '</div>' +
      '<div class="value">' + value + '<small>' + (unit || '') + '</small></div>' +
      '<div class="foot">' + (foot || '') + '</div></div>';
  }

  function kpiYen(label, amount, foot, cls) {
    const p = fmt.yenParts(amount);
    return '<div class="kpi ' + (cls || '') + '">' +
      '<div class="label">' + label + '</div>' +
      '<div class="value">' + p.v + '<small>' + p.u + '</small></div>' +
      '<div class="foot">' + (foot || '') + '</div></div>';
  }

  /* ---------------- 订单详情抽屉 ---------------- */
  async function orderDetail(orderNo) {
    try {
      const [d, validation] = await Promise.all([
        get('/api/purchase-orders/' + encodeURIComponent(orderNo)),
        get('/api/purchase-orders/' + encodeURIComponent(orderNo) + '/validation').catch(function () { return null; })
      ]);

      const vehicleRows = (d.vehicles || []).map(function (v) {
        return '<tr>' +
          '<td class="mono" style="font-weight:600">' + v.vin + '</td>' +
          '<td>' + (v.model || '-') + '<div class="muted" style="font-size:11px">' +
          (v.modelYear || '-') + '年 · ' + (v.color || '-') + '</div></td>' +
          '<td class="num">' + fmt.yen(v.invoiceAmount) + '</td>' +
          '<td>' + badge(v.status) + '</td>' +
          '<td>' + badge(v.purchaseChannel) + '</td>' +
          '<td class="muted">' + (v.supplierName || '—') + '</td>' +
          '</tr>';
      }).join('') || '<tr><td colspan="6" class="empty">该订单下暂无车辆</td></tr>';

      // 校验链：两套校验链按打款模式分叉，页面要把"当前走哪套"讲清楚
      let validationHtml = '';
      if (validation) {
        const items = (validation.items || []).map(function (it) {
          const ok = it.passed;
          return '<tr>' +
            '<td>' + (ok ? '<span class="badge ok">通过</span>' : '<span class="badge danger">未通过</span>') + '</td>' +
            '<td style="font-weight:600">' + it.name + '</td>' +
            '<td class="muted mono" style="font-size:11px">' + it.key + '</td>' +
            '<td style="color:#5b6678">' + (ok ? '—' : it.reason) + '</td>' +
            '</tr>';
        }).join('');
        const modeHint = validation.payeeMode === 'SELF_PAY'
          ? '<div style="padding:9px 12px;background:#fdf3e2;border-radius:6px;font-size:12px;color:#c97a08;margin-bottom:10px">' +
            '本单走 <b>回款经销商</b> 校验链（营运资金场景）：不校验三方委托付款协议，改校验库存实物在库与独立抵押率。</div>'
          : '<div style="padding:9px 12px;background:#e8eefa;border-radius:6px;font-size:12px;color:#24407f;margin-bottom:10px">' +
            '本单走 <b>代付上游</b> 校验链：必须有三方委托付款协议生效且金额不超授权上限。</div>';
        validationHtml =
          '<div class="section-title">放款前校验链（' +
          (validation.payeeModeLabel || validation.payeeMode) + '）</div>' +
          modeHint +
          '<div class="table-wrap"><table class="grid"><thead><tr>' +
          '<th>结果</th><th>校验项</th><th>标识</th><th>说明</th>' +
          '</tr></thead><tbody>' + items + '</tbody></table></div>';
      }

      FP.drawer('采购订单 · ' + orderNo,
        '<div class="section-title">订单主信息</div>' +
        '<dl class="dl">' +
        '<dt>采购订单号</dt><dd class="mono">' + d.orderNo + '</dd>' +
        '<dt>经销商</dt><dd>' + dealerName(d.dealerId) + '</dd>' +
        '<dt>供应商类型</dt><dd>' + badge(d.supplierType) + ' ' + (d.supplierTypeLabel || '') + '</dd>' +
        '<dt>打款模式</dt><dd>' + FP.payeeModeBadge(d.payeeMode) +
        '<span class="muted" style="font-size:11px">（由供应商类型派生，不可人工覆盖）</span></dd>' +
        '<dt>收款账户</dt><dd class="mono">' + (d.payeeAccountSnapshot || '—') + '</dd>' +
        '<dt>采购总额</dt><dd>' + fmt.yenFull(d.totalAmount) + '（' + (d.vehicleCount || 0) + ' 台）</dd>' +
        '<dt>采购日</dt><dd>' + fmt.date(d.purchasedAt) + '</dd>' +
        '<dt>契约/发注书号</dt><dd class="mono">' + (d.evidenceNo || '—') + '</dd>' +
        '<dt>导入通道</dt><dd>' + badge(d.ingestChannel) + '</dd>' +
        '<dt>订单状态</dt><dd>' + badge(d.status) + '</dd>' +
        '<dt>创建时间</dt><dd>' + fmt.datetime(d.createdAt) + '</dd>' +
        '</dl>' +

        '<div class="section-title">车辆清单（一单多车）</div>' +
        '<div class="table-wrap"><table class="grid"><thead><tr>' +
        '<th>VIN</th><th>车型</th><th class="num">采购金额</th><th>状态</th><th>采购渠道</th><th>上游</th>' +
        '</tr></thead><tbody>' + vehicleRows + '</tbody></table></div>' +

        validationHtml +

        '<div class="section-title">原始凭证</div>' +
        (d.evidenceAttachmentId
          ? '<button class="btn sm ghost" onclick="FP.poViews.openAttachment(' + d.evidenceAttachmentId + ')">查看附件 #' +
            d.evidenceAttachmentId + '</button>' +
            '<div class="hint" style="margin-top:6px;font-size:11px;color:#8a94a6">' +
            '凭证文件存对象存储（Object Lock 不可删改），库内只存元数据；访问链接为短时效预签名。</div>'
          : '<div style="padding:9px 12px;background:#f0f3f8;border-radius:6px;font-size:12px;color:#5b6678">' +
            '本单未关联凭证附件。原始凭证（契约・発注書・請求書）必须留存——只存解析结果无法自证。</div>'));
    } catch (e) {
      toast('加载订单详情失败：' + e.message, 'err');
    }
  }

  /* ---------------- 附件查看 ---------------- */
  async function openAttachment(attachmentId) {
    try {
      const r = await get('/api/attachments/' + attachmentId + '/url');
      window.open(r.url, '_blank');
    } catch (e) {
      toast('获取附件链接失败：' + e.message, 'err');
    }
  }

  /* ============================================================
     导入：两段式（preview → commit）
     ============================================================ */
  async function openImport() {
    let dealers = [];
    try {
      dealers = await loadDealers();
    } catch (e) {
      toast('加载经销商失败：' + e.message, 'err');
      return;
    }

    const dealerOptions = dealers.map(function (d) {
      return { value: d.id, label: d.name + '（' + d.dealerCode + '）' };
    });

    const wrap = document.createElement('div');

    // 第一步：选文件 + 选经销商
    const step1 = document.createElement('div');
    step1.innerHTML =
      '<div class="field"><label>归属经销商 *</label>' +
      '<select class="input" id="poImportDealer" style="width:100%">' +
      dealerOptions.map(function (o) { return '<option value="' + o.value + '">' + o.label + '</option>'; }).join('') +
      '</select><div class="hint">导入的车辆将挂到该经销商名下与授信额度上。</div></div>' +
      '<div class="field"><label>采购清单文件 *</label>' +
      '<input type="file" id="poImportFile" class="input" accept=".xlsx,.xls,.csv" style="width:100%">' +
      '<div class="hint">支持 Excel（.xlsx）与 CSV。列头必须与模板完全一致，' +
      '<b>列头不符会直接拒绝整个文件</b>（不逐行猜测）。</div></div>' +
      '<button class="btn sm" id="poPreviewBtn">① 预校验（不落库）</button>';
    wrap.appendChild(step1);

    // 第二步：预览结果（预校验后显示）
    const step2 = document.createElement('div');
    step2.id = 'poImportResult';
    step2.style.marginTop = '16px';
    wrap.appendChild(step2);

    const modalRef = FP.modal({
      title: '导入采购清单（Excel / CSV）',
      content: wrap,
      okText: '② 确认落库',
      onOk: function () { return false; }   // 落库走自定义按钮，避免误点
    });

    // 预校验
    step1.querySelector('#poPreviewBtn').addEventListener('click', async function () {
      const fileInput = step1.querySelector('#poImportFile');
      const dealerId = step1.querySelector('#poImportDealer').value;
      const file = fileInput.files && fileInput.files[0];
      if (!file) { toast('请选择采购清单文件', 'warn'); return; }

      const btn = this;
      btn.disabled = true;
      btn.textContent = '校验中…';
      try {
        const form = new FormData();
        form.append('file', file);
        form.append('mode', 'preview');
        form.append('dealerId', dealerId);

        const token = localStorage.getItem('fp_token');
        const resp = await fetch(FP.api.base + '/api/purchase-orders/import', {
          method: 'POST',
          headers: {
            'Authorization': 'Bearer ' + token,
            'X-Operator-Id': localStorage.getItem('fp_username') || 'unknown'
          },
          body: form
        });
        const body = await resp.json();
        if (!resp.ok || body.code !== 'OK') {
          throw new Error(body.message || ('HTTP ' + resp.status));
        }
        renderPreview(step2, body.data, file, dealerId);
      } catch (e) {
        step2.innerHTML = '<div class="empty" style="color:#c0392b">' + e.message + '</div>';
      } finally {
        btn.disabled = false;
        btn.textContent = '① 预校验（不落库）';
      }
    });
  }

  /** 渲染预校验结果与行级错误明细 */
  function renderPreview(container, data, file, dealerId) {
    const errors = data.errors || [];
    let html =
      '<div class="section-title">预校验结果</div>' +
      '<div style="display:flex;gap:16px;flex-wrap:wrap;margin-bottom:12px;font-size:12.5px">' +
      '<span>总行数 <b>' + data.totalRows + '</b></span>' +
      '<span style="color:#0e9a6a">通过 <b>' + data.acceptedRows + '</b></span>' +
      '<span style="color:#c0392b">拒绝 <b>' + data.rejectedRows + '</b></span>' +
      '<span>金额合计 <b>' + fmt.yenFull(data.totalAmount) + '</b></span>' +
      '</div>';

    if (errors.length > 0) {
      const errorRows = errors.map(function (e) {
        return '<tr>' +
          '<td class="num">' + e[0] + '</td>' +
          '<td class="mono">' + (e[1] || '—') + '</td>' +
          '<td style="color:#c0392b">' + e[2] + '</td>' +
          '<td class="muted mono" style="font-size:11px">' + (e[3] === null || e[3] === undefined ? '—' : e[3]) + '</td>' +
          '</tr>';
      }).join('');
      html +=
        '<div style="padding:9px 12px;background:#fdecea;border-radius:6px;font-size:12px;color:#c0392b;margin-bottom:10px">' +
        '有 <b>' + errors.length + '</b> 处行级错误。这些行<b>不会被导入</b>，其余通过的行会正常建单。' +
        '请修正后重新上传——错误精确到行号与字段。</div>' +
        '<div class="table-wrap" style="max-height:280px;overflow-y:auto"><table class="grid"><thead><tr>' +
        '<th class="num">行号</th><th>字段</th><th>原因</th><th>原值</th>' +
        '</tr></thead><tbody>' + errorRows + '</tbody></table></div>';
    } else {
      html += '<div style="padding:9px 12px;background:#e6f6f0;border-radius:6px;font-size:12px;color:#0e9a6a;margin-bottom:10px">' +
        '全部通过预校验，无行级错误。</div>';
    }

    html +=
      '<div style="margin-top:12px;display:flex;gap:8px">' +
      '<button class="btn sm" id="poCommitBtn"' + (data.acceptedRows > 0 ? '' : ' disabled') + '>确认落库 ' +
      data.acceptedRows + ' 台</button>' +
      '<button class="btn sm ghost" id="poDownloadErr">下载错误明细</button>' +
      '</div>' +
      '<div class="hint" style="margin-top:8px;font-size:11px;color:#8a94a6">' +
      '落库后：订单按采购订单号幂等（重复导入不重复建单）；VIN 幂等（同一台车不会被两个订单重复报）。</div>';

    container.innerHTML = html;

    // 下载错误明细（CSV）
    container.querySelector('#poDownloadErr').addEventListener('click', function () {
      const rows = [['行号', '字段', '原因', '原值']].concat(errors);
      const csv = rows.map(function (r) {
        return r.map(function (c) { return '"' + String(c === null || c === undefined ? '' : c).replace(/"/g, '""') + '"'; }).join(',');
      }).join('\n');
      const blob = new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8' });
      const a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = '导入错误明细_' + Date.now() + '.csv';
      a.click();
      URL.revokeObjectURL(a.href);
    });

    // 确认落库
    const commitBtn = container.querySelector('#poCommitBtn');
    if (commitBtn) {
      commitBtn.addEventListener('click', async function () {
        if (!confirm('确认将 ' + data.acceptedRows + ' 台车落库？')) return;
        commitBtn.disabled = true;
        commitBtn.textContent = '落库中…';
        try {
          const form = new FormData();
          form.append('file', file);
          form.append('mode', 'commit');
          form.append('dealerId', dealerId);

          const token = localStorage.getItem('fp_token');
          const resp = await fetch(FP.api.base + '/api/purchase-orders/import', {
            method: 'POST',
            headers: {
              'Authorization': 'Bearer ' + token,
              'X-Operator-Id': localStorage.getItem('fp_username') || 'unknown'
            },
            body: form
          });
          const body = await resp.json();
          if (!resp.ok || body.code !== 'OK') {
            throw new Error(body.message || ('HTTP ' + resp.status));
          }
          const r = body.data;
          toast('落库完成：新建 ' + r.createdOrders + ' 单 / ' + r.acceptedRows + ' 台，跳过 ' +
            r.skippedOrders + ' 单（幂等），拒绝 ' + r.rejectedRows + ' 行', 'ok');
          invalidate();
          await FP.app.refresh();
          const mask = document.querySelector('.modal-mask');
          if (mask) mask.remove();
        } catch (e) {
          toast('落库失败：' + e.message, 'err');
          commitBtn.disabled = false;
          commitBtn.textContent = '确认落库';
        }
      });
    }
  }

  /** 下载 Excel 模板（生成 CSV 模板，Excel 可直接打开） */
  function downloadTemplate() {
    const header = 'orderNo,vin,model,modelYear,color,supplierType,invoiceAmount,payeeAccountRef,vehicleStatus,evidenceNo,attachmentId';
    const sample = [
      'PO-20261026-001,JMAAB1V1XXXXXXXX1,AQUA,2026,WHITE,REGIONAL_AGENCY,1850000,ACC-KY-001,待放款,CT-2026-1026,',
      'PO-20261026-001,JMAAB1V1XXXXXXXX2,NOTE,2026,BLACK,REGIONAL_AGENCY,2100000,ACC-KY-001,待放款,CT-2026-1026,'
    ].join('\n');
    const notes =
      '\n\n' +
      '# 列说明（列名必须完全一致，顺序可调；列头不符将拒绝整个文件）\n' +
      '# orderNo          采购订单号（必填，唯一，幂等锚点）\n' +
      '# vin              车架号（必填，唯一；VIN 幂等：同一台车不会被两个订单重复报）\n' +
      '# model            车型（必填）\n' +
      '# modelYear        年款（必填，4 位）\n' +
      '# color            颜色（必填）\n' +
      '# supplierType     供应商类型（必填）：MAKER_SALES_CO 制造商销售公司 / REGIONAL_AGENCY 区域总代理 /\n' +
      '#                  WHOLESALER 批发公司 / AUCTION 拍卖行 / USED_WHOLESALER 中古车批发商 / DEALER_SELF 经销商自身\n' +
      '# invoiceAmount    采购金额（必填，整数日元，不允许小数）\n' +
      '# payeeAccountRef  收款账户引用码（必填）。只填业务引用码，不要写明文账号——系统按引用查账户主数据\n' +
      '# vehicleStatus    车辆状态（必填）：待放款 / 在途 / 在库 / 已质押 / 已解押。\n' +
      '#                  待放款=台账已建、钱尚未付（新单默认值）；在途=已付款尚未到货；\n' +
      '#                  在库=已到店入库。不要为了"看起来有进度"把未付款的车填成在途，\n' +
      '#                  那会让系统误判资金已占用。\n' +
      '# evidenceNo       契约/发注书/请求书号（选填）\n' +
      '# attachmentId     凭证附件 ID（选填，先在附件区上传后回填）\n';

    const blob = new Blob(['﻿' + header + '\n' + sample + notes], { type: 'text/csv;charset=utf-8' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = '采购清单导入模板.csv';
    a.click();
    URL.revokeObjectURL(a.href);
    toast('模板已下载（CSV 格式，Excel 可直接打开）', 'ok');
  }

  /* ============================================================
     视图：上游与账户
     ============================================================ */
  async function suppliers(root) {
    root.innerHTML = loading('正在加载上游与账户…');
    try {
      const [sups, accounts] = await Promise.all([loadSuppliers(), loadAccounts()]);
      // 上游准入属管理决策；账户录入属导入流程配套——两个权限点分开
      const canMaintainSupplier = FP.auth.can('SUPPLIER_MAINTAIN');

      const directCount = sups.filter(function (s) { return s.payeeMode === 'DIRECT_PAY'; }).length;
      const selfCount = sups.filter(function (s) { return s.payeeMode === 'SELF_PAY'; }).length;
      const activeAccounts = accounts.filter(function (a) { return a.status === 'ACTIVE'; }).length;

      const supRows = sups.map(function (s) {
        return '<tr>' +
          '<td class="mono" style="font-weight:600">' + s.supplierCode + '</td>' +
          '<td>' + s.name + '</td>' +
          '<td>' + badge(s.supplierType) + '</td>' +
          '<td>' + FP.payeeModeBadge(s.payeeMode) + '</td>' +
          '<td>' + (s.guaranteeAbility ? badge(s.guaranteeAbility) : '<span class="muted">未评估</span>') + '</td>' +
          '<td>' + badge(s.riskGrade) + '</td>' +
          '<td>' + FP.dealerBadge(s.status) + '</td>' +
          '<td class="muted">' + (s.contactPerson || '—') + '</td>' +
          '<td><button class="btn sm ghost" onclick="FP.poViews.editSupplier(' + s.id + ')">编辑</button> ' +
          '<button class="btn sm ghost" onclick="FP.poViews.supplierAccounts(' + s.id + ')">账户</button></td>' +
          '</tr>';
      }).join('') || '<tr><td colspan="9" class="empty">暂无上游主数据</td></tr>';

      const accRows = accounts.map(function (a) {
        const cls = a.status === 'ACTIVE' ? 'ok' : (a.status === 'FROZEN' ? 'danger' : 'warn');
        return '<tr>' +
          '<td class="mono">' + (a.accountRef || '—') + '</td>' +
          '<td>' + (a.ownerType === 'SUPPLIER' ? '上游账户' : '经销商结算账户') + '</td>' +
          '<td>' + (a.bankName || '—') + '</td>' +
          '<td>' + (a.accountHolder || '—') + '</td>' +
          '<td class="mono muted">' + (a.accountNoMask || '—') + '</td>' +
          '<td><span class="badge ' + cls + '">' + (a.statusLabel || a.status) + '</span></td>' +
          '<td class="muted">' + fmt.date(a.effectiveFrom) + '</td>' +
          '<td class="muted">' + (a.reviewer || '—') + '</td>' +
          '<td>' + (a.status === 'ACTIVE'
            ? '<button class="btn sm ghost" onclick="FP.poViews.freezeAccount(' + a.id + ')">冻结</button>'
            : '<button class="btn sm ghost" onclick="FP.poViews.activateAccount(' + a.id + ')">恢复</button>') + '</td>' +
          '</tr>';
      }).join('') || '<tr><td colspan="9" class="empty">暂无收款账户</td></tr>';

      root.innerHTML =
        '<div class="kpi-grid">' +
        kpi('上游总数', sups.length, '家', '制造商销售公司 / 总代理 / 批发 / 拍卖', '') +
        kpi('代付上游', directCount, '家', '资金直付上游', 'ok') +
        kpi('回款经销商', selfCount, '家', '库存抵押周转 · 营运资金', selfCount > 0 ? 'warn' : '') +
        kpi('生效账户', activeAccounts, '个', '共 ' + accounts.length + ' 个账户', '') +
        '</div>' +

        '<div class="card"><header><h2>上游主数据</h2>' +
        '<span class="tag">打款模式由供应商类型派生</span>' +
        '<div class="tools">' +
        (canMaintainSupplier
          ? '<button class="btn sm" onclick="FP.poViews.createSupplier()">新增上游</button>'
          : '<span class="muted" style="font-size:11px">无上游准入权限</span>') +
        '</div></header>' +
        '<div class="body flush"><div class="table-wrap"><table class="grid"><thead><tr>' +
        '<th>上游编号</th><th>名称</th><th>供应商类型</th><th>打款模式</th><th>回购/担保能力</th>' +
        '<th>评级</th><th>状态</th><th>联系人</th><th>操作</th>' +
        '</tr></thead><tbody>' + supRows + '</tbody></table></div></div></div>' +

        '<div class="card"><header><h2>收款账户</h2>' +
        '<span class="tag">账号明文不落库 · 仅显示掩码</span>' +
        '<div class="tools"><button class="btn sm" onclick="FP.poViews.createDealerAccount()">新增经销商账户</button></div></header>' +
        '<div class="body flush"><div class="table-wrap"><table class="grid"><thead><tr>' +
        '<th>账户引用码</th><th>归属</th><th>开户行</th><th>户名</th><th>账号</th>' +
        '<th>状态</th><th>生效日</th><th>复核人</th><th>操作</th>' +
        '</tr></thead><tbody>' + accRows + '</tbody></table></div></div></div>';
    } catch (e) {
      root.innerHTML = errBox(e.message);
    }
  }

  /* ---------------- 上游操作 ---------------- */
  function createSupplier() {
    FP.formModal({
      title: '新增上游',
      fields: [
        { key: 'supplierCode', label: '上游编号', required: true, placeholder: '如 SUP-001' },
        { key: 'name', label: '上游名称', required: true },
        { key: 'supplierType', label: '供应商类型', type: 'select', value: 'REGIONAL_AGENCY',
          options: FP.SUPPLIER_TYPE_OPTIONS,
          hint: '供应商类型决定打款模式：前五类为代付上游（需三方委托付款协议），DEALER_SELF 为回款经销商（营运资金）' },
        { key: 'guaranteeAbility', label: '回购/担保能力', type: 'select', value: 'LIMITED',
          options: [
            { value: 'STRONG', label: '强（厂商回购）' },
            { value: 'LIMITED', label: '有限' },
            { value: 'NONE', label: '无' }
          ],
          hint: '中间层级与拍卖行普遍没有厂商回购，不可一刀切按「强」处理' },
        { key: 'riskGrade', label: '上游评级', placeholder: 'A/B/C/D，可空' },
        { key: 'contactPerson', label: '联系人' },
        { key: 'contactPhone', label: '联系电话' },
        { key: 'remark', label: '备注', type: 'textarea' }
      ],
      okText: '创建',
      onOk: async function (v) {
        try {
          await post('/api/suppliers', v);
          toast('上游已创建', 'ok');
          invalidate();
          await FP.app.refresh();
          return true;
        } catch (e) { toast('创建失败：' + e.message, 'err'); return false; }
      }
    });
  }

  async function editSupplier(id) {
    let s;
    try {
      const list = await loadSuppliers();
      s = list.filter(function (x) { return x.id === id; })[0];
    } catch (e) { toast('加载上游失败：' + e.message, 'err'); return; }
    if (!s) { toast('上游不存在', 'err'); return; }

    FP.formModal({
      title: '编辑上游 · ' + s.name,
      fields: [
        { key: 'name', label: '上游名称', value: s.name, required: true },
        { key: 'supplierType', label: '供应商类型', type: 'select', value: s.supplierType,
          options: FP.SUPPLIER_TYPE_OPTIONS,
          hint: '改类型会改变打款模式与放款校验链，已有放款不受影响（快照已冻结）' },
        { key: 'guaranteeAbility', label: '回购/担保能力', type: 'select', value: s.guaranteeAbility || 'LIMITED',
          options: [
            { value: 'STRONG', label: '强（厂商回购）' },
            { value: 'LIMITED', label: '有限' },
            { value: 'NONE', label: '无' }
          ] },
        { key: 'riskGrade', label: '上游评级', value: s.riskGrade || '' },
        { key: 'contactPerson', label: '联系人', value: s.contactPerson || '' },
        { key: 'contactPhone', label: '联系电话', value: s.contactPhone || '' },
        { key: 'remark', label: '备注', type: 'textarea', value: s.remark || '' }
      ],
      okText: '保存',
      onOk: async function (v) {
        try {
          await put('/api/suppliers/' + id, v);
          toast('已保存', 'ok');
          invalidate();
          await FP.app.refresh();
          return true;
        } catch (e) { toast('保存失败：' + e.message, 'err'); return false; }
      }
    });
  }

  /** 查看某上游的账户，并可新增 */
  async function supplierAccounts(supplierId) {
    let sup;
    try {
      const list = await loadSuppliers();
      sup = list.filter(function (x) { return x.id === supplierId; })[0];
    } catch (e) { toast('加载失败：' + e.message, 'err'); return; }
    if (!sup) { toast('上游不存在', 'err'); return; }

    let accounts = [];
    try {
      accounts = (await loadAccounts()).filter(function (a) { return a.supplierId === supplierId; });
    } catch (e) { /* 忽略 */ }

    const rows = accounts.map(function (a) {
      return '<tr>' +
        '<td class="mono">' + (a.accountRef || '—') + '</td>' +
        '<td>' + (a.bankName || '—') + '</td>' +
        '<td>' + (a.accountHolder || '—') + '</td>' +
        '<td class="mono muted">' + (a.accountNoMask || '—') + '</td>' +
        '<td>' + badge(a.status) + '</td>' +
        '<td class="muted">' + fmt.date(a.effectiveFrom) + '</td>' +
        '</tr>';
    }).join('') || '<tr><td colspan="6" class="empty">该上游尚无收款账户</td></tr>';

    const dr = FP.drawer('收款账户 · ' + sup.name,
      '<div style="padding:9px 12px;background:#e8eefa;border-radius:6px;font-size:12px;color:#24407f;margin-bottom:12px">' +
      '账号明文<b>不落库</b>（AES-GCM 加密），页面只显示掩码。' +
      '<b>账户引用码</b>是采购清单里要填的值——清单不接受明文账号。</div>' +
      '<div class="table-wrap"><table class="grid"><thead><tr>' +
      '<th>引用码</th><th>开户行</th><th>户名</th><th>账号</th><th>状态</th><th>生效日</th>' +
      '</tr></thead><tbody>' + rows + '</tbody></table></div>' +
      '<div style="margin-top:14px">' +
      (FP.auth.can('BANK_ACCOUNT_MAINTAIN')
        ? '<button class="btn sm" onclick="FP.poViews.createSupplierAccount(' + supplierId + ')">新增账户</button>'
        : '<span class="muted" style="font-size:11px">无账户录入权限</span>') + '</div>');

    // 抽屉内的按钮需要能关闭并刷新
    setTimeout(function () {
      dr.body.querySelector('button').addEventListener('click', function () {
        dr.close();
      });
    }, 0);
  }

  function createSupplierAccount(supplierId) {
    FP.formModal({
      title: '新增上游收款账户',
      fields: [
        { key: 'bankName', label: '开户行', required: true },
        { key: 'accountHolder', label: '户名', required: true },
        { key: 'accountNo', label: '账号', required: true,
          hint: '明文只在本次提交中出现，落库即加密；页面此后只显示掩码' },
        { key: 'accountRef', label: '账户引用码',
          placeholder: '留空自动生成，如 ACC-KY-001',
          hint: '采购清单里填的就是这个 —— 清单不接受明文账号' },
        { key: 'effectiveFrom', label: '生效日', type: 'date', value: new Date().toISOString().substring(0, 10) }
      ],
      okText: '创建',
      onOk: async function (v) {
        try {
          const r = await post('/api/suppliers/' + supplierId + '/accounts', v);
          toast('账户已创建，引用码 ' + r.accountRef, 'ok');
          invalidate();
          await FP.app.refresh();
          return true;
        } catch (e) { toast('创建失败：' + e.message, 'err'); return false; }
      }
    });
  }

  function createDealerAccount() {
    let dealers = [];
    try { dealers = cache.dealers || []; } catch (e) { /* 忽略 */ }
    if (!dealers.length) {
      toast('请先在经销商页加载经销商列表', 'warn');
      return;
    }
    FP.formModal({
      title: '新增经销商结算账户（场景 C）',
      fields: [
        { key: 'dealerId', label: '经销商', type: 'select',
          options: dealers.map(function (d) { return { value: d.id, label: d.name }; }) },
        { key: 'bankName', label: '开户行', required: true },
        { key: 'accountHolder', label: '户名', required: true },
        { key: 'accountNo', label: '账号', required: true },
        { key: 'accountRef', label: '账户引用码', placeholder: '留空自动生成' },
        { key: 'effectiveFrom', label: '生效日', type: 'date', value: new Date().toISOString().substring(0, 10) }
      ],
      okText: '创建',
      onOk: async function (v) {
        try {
          const r = await post('/api/dealers/' + v.dealerId + '/bank-accounts', {
            bankName: v.bankName, accountHolder: v.accountHolder, accountNo: v.accountNo,
            accountRef: v.accountRef, effectiveFrom: v.effectiveFrom
          });
          toast('结算账户已创建，引用码 ' + r.accountRef, 'ok');
          invalidate();
          await FP.app.refresh();
          return true;
        } catch (e) { toast('创建失败：' + e.message, 'err'); return false; }
      }
    });
  }

  async function freezeAccount(id) {
    try {
      await put('/api/bank-accounts/' + id + '/status', { status: 'FROZEN' });
      toast('账户已冻结', 'ok');
      invalidate();
      await FP.app.refresh();
    } catch (e) { toast('操作失败：' + e.message, 'err'); }
  }

  async function activateAccount(id) {
    try {
      await put('/api/bank-accounts/' + id + '/status', { status: 'ACTIVE' });
      toast('账户已恢复', 'ok');
      invalidate();
      await FP.app.refresh();
    } catch (e) { toast('操作失败：' + e.message, 'err'); }
  }

  /* ---------------- 暴露 ---------------- */
  global.FP.poViews = {
    purchaseOrders: purchaseOrders,
    suppliers: suppliers,
    orderDetail: orderDetail,
    openAttachment: openAttachment,
    openImport: openImport,
    downloadTemplate: downloadTemplate,
    createSupplier: createSupplier,
    editSupplier: editSupplier,
    supplierAccounts: supplierAccounts,
    createSupplierAccount: createSupplierAccount,
    createDealerAccount: createDealerAccount,
    freezeAccount: freezeAccount,
    activateAccount: activateAccount,
    invalidate: invalidate
  };
})(window);
