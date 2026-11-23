# -*- coding: utf-8 -*-
"""
浏览器端到端测试 —— 用真实 Chrome 走管理控制台（网关 18080）。

用例覆盖：
  1) 登录页：打开 / 错误密码 / 正确登录 / 令牌与角色写入 localStorage
  2) 控制台：11 个导航视图逐个加载，断言非空、无「页面加载失败/加载失败」
  3) 顶栏：用户名、角色徽章、令牌倒计时
  4) 刷新按钮 / 全局刷新
  5) 权限：admin 与 operator 可见写按钮差异
  6) 退出登录
  7) 全程收集 console.error / pageerror / HTTP>=400 / 请求失败

运行前置：所有服务已通过 run-microservices.sh start 启动。
运行：python browser-test.py
输出：browser-test-results/ 下截图 + browser-test-report.json
"""
import json
import os
import sys
import time
from datetime import datetime
from pathlib import Path

from playwright.sync_api import sync_playwright

BASE = os.environ.get("BROWSER_TEST_BASE", "http://127.0.0.1:18080")
ROOT = Path(__file__).resolve().parent
OUT = ROOT / "browser-test-results"
OUT.mkdir(exist_ok=True)

ROUTES = [
    ("dashboard", "经营总览", "view-dashboard"),
    ("dealers", "经销商", "view-dealers"),
    ("credit-lines", "授信额度", "view-creditLines"),
    ("vehicles", "库存车辆", "view-vehicles"),
    ("audits", "盘点监管", "view-audits"),
    ("purchase-orders", "采购订单", "view-purchaseOrders"),
    ("suppliers", "上游与账户", "view-suppliers"),
    ("alerts", "风险告警", "view-alerts"),
    ("jobs", "批量作业", "view-jobs"),
    ("ratings", "评级口径", "view-ratingConfigs"),
    ("account", "我的凭证", "view-account"),
]

results = []
console_errors = []
page_errors = []
bad_responses = []
request_failures = []


def record(name, ok, detail=""):
    results.append({"case": name, "pass": bool(ok), "detail": str(detail)})
    flag = "PASS" if ok else "FAIL"
    print("[%s] %s%s" % (flag, name, (" :: " + str(detail)) if detail else ""), flush=True)


def attach_collectors(page):
    def on_console(msg):
        if msg.type == "error":
            console_errors.append(msg.text)

    def on_pageerror(err):
        page_errors.append(str(err))

    def on_response(resp):
        if resp.status >= 400:
            bad_responses.append({"url": resp.url, "status": resp.status})

    def on_requestfailed(req):
        f = req.failure
        request_failures.append({"url": req.url, "failure": f})

    page.on("console", on_console)
    page.on("pageerror", on_pageerror)
    page.on("response", on_response)
    page.on("requestfailed", on_requestfailed)


def clear_collectors():
    console_errors.clear()
    page_errors.clear()
    bad_responses.clear()
    request_failures.clear()


def login(page, username, password):
    page.goto(BASE + "/login.html", wait_until="domcontentloaded", timeout=30000)
    page.evaluate("localStorage.clear()")
    page.reload(wait_until="domcontentloaded")
    page.fill("#username", username)
    page.fill("#password", password)
    page.click("#btnLogin")
    try:
        page.wait_for_url("**/index.html", timeout=15000)
        return True
    except Exception:
        return False


def run():
    started = datetime.now().strftime("%Y%m%d-%H%M%S")
    with sync_playwright() as p:
        browser = p.chromium.launch(channel="chrome", headless=True,
                                    args=["--no-sandbox", "--disable-dev-shm-usage"])
        ctx = browser.new_context(viewport={"width": 1440, "height": 900})
        page = ctx.new_page()
        attach_collectors(page)

        # ---------- 1. 登录页 ----------
        clear_collectors()
        page.goto(BASE + "/login.html", wait_until="domcontentloaded", timeout=30000)
        record("登录页可访问", page.locator("#loginForm").count() == 1)
        record("登录页标题正确", "库存融资核心系统" in page.title())
        page.screenshot(path=str(OUT / "01-login.png"), full_page=True)

        # 错误密码
        page.fill("#username", "admin")
        page.fill("#password", "wrong-password")
        page.click("#btnLogin")
        try:
            page.wait_for_function(
                "document.getElementById('errorBox').style.display === 'block'",
                timeout=8000)
            err_text = page.locator("#errorBox").inner_text()
            record("错误密码被拒绝并提示", len(err_text.strip()) > 0, err_text[:80])
        except Exception as e:
            record("错误密码被拒绝并提示", False, str(e)[:120])
        page.screenshot(path=str(OUT / "02-login-error.png"), full_page=True)

        # 正确登录
        page.fill("#username", "admin")
        page.fill("#password", "admin123")
        page.click("#btnLogin")
        try:
            page.wait_for_url("**/index.html", timeout=15000)
            record("admin 登录成功跳转", True)
        except Exception as e:
            record("admin 登录成功跳转", False, str(e)[:120])
            browser.close()
            return
        page.wait_for_selector("#nav .nav-item", timeout=10000)
        token = page.evaluate("localStorage.getItem('fp_token')")
        record("令牌写入 localStorage", bool(token and token.count(".") == 2))
        who = page.locator("#whoami").inner_text()
        record("顶栏显示登录名", who == "admin", who)
        role_chip = page.locator("#roleChip").inner_text()
        record("角色徽章渲染", len(role_chip.strip()) > 0, role_chip)
        token_chip = page.locator("#tokenChip").inner_text()
        record("令牌倒计时渲染", "令牌剩余" in token_chip, token_chip)
        page.screenshot(path=str(OUT / "03-dashboard.png"), full_page=True)

        # ---------- 2. 11 个视图 ----------
        for idx, (route, label, view_id) in enumerate(ROUTES, start=1):
            clear_collectors()
            page.evaluate("(r) => { location.hash = '#/' + r; }", route)
            # 等待视图激活且不再处于 loading
            try:
                page.wait_for_function(
                    "(vid) => { const v=document.getElementById(vid); "
                    "return v && v.classList.contains('active') && "
                    "!v.querySelector('.loading') && v.innerText.trim().length > 0; }",
                    arg=view_id, timeout=20000)
                ok = True
                detail = ""
            except Exception as e:
                ok = False
                detail = "视图未渲染: " + str(e)[:100]
            active_text = page.evaluate(
                "(vid) => { const v=document.getElementById(vid); return v ? v.innerText.trim() : ''; }",
                view_id)
            if "页面加载失败" in active_text or "加载失败：" in active_text:
                ok = False
                detail = "页面内容含加载失败: " + active_text[:120]
            record("视图[%s]渲染" % label, ok, detail)
            page.screenshot(path=str(OUT / ("%02d-%s.png" % (idx + 3, route))), full_page=True)

            # 每个视图单独记一次该视图期间产生的错误
            if console_errors:
                record("视图[%s]无 console error" % label, False, " | ".join(console_errors[:3])[:200])
            if page_errors:
                record("视图[%s]无 JS 异常" % label, False, " | ".join(page_errors[:3])[:200])
            failed_api = [b for b in bad_responses if "/api/" in b["url"] and b["status"] >= 400]
            if failed_api:
                record("视图[%s]无失败 API" % label, False,
                       "; ".join("%s %s" % (b["status"], b["url"].split("/api/")[-1]) for b in failed_api[:3])[:200])

        # ---------- 3. 全局刷新 ----------
        clear_collectors()
        page.click("#refreshBtn")
        page.wait_for_timeout(1200)
        record("全局刷新按钮可点击", True)
        if page_errors or console_errors:
            record("全局刷新无 JS 异常", False, " | ".join((page_errors + console_errors)[:3])[:200])

        # ---------- 4. admin 写按钮 ----------
        page.evaluate("location.hash = '#/dealers'")
        page.wait_for_function(
            "() => { const v=document.getElementById('view-dealers'); "
            "return v && v.classList.contains('active') && !v.querySelector('.loading'); }",
            timeout=15000)
        admin_buttons = page.locator("#view-dealers button").all_inner_texts()
        admin_has_create = any("新增经销商" in b for b in admin_buttons)
        record("admin 可见「新增经销商」写按钮", admin_has_create, str(admin_buttons[:6]))

        # ---------- 4b. 交互弹窗 / 抽屉 ----------
        def close_overlays():
            for sel in (".modal-mask .modal header .x", ".drawer-mask ~ .drawer header .x", ".drawer header .x"):
                try:
                    if page.locator(sel).count() > 0:
                        page.locator(sel).first.click()
                        page.wait_for_timeout(150)
                except Exception:
                    pass
            page.evaluate("() => { document.querySelectorAll('.modal-mask,.drawer-mask,.drawer').forEach(x=>x.remove()); }")

        # 新增经销商弹窗（只打开，不提交，避免污染数据）
        clear_collectors()
        try:
            page.locator("#view-dealers button", has_text="新增经销商").first.click()
            page.wait_for_selector(".modal-mask .modal", timeout=8000)
            fields = page.locator(".modal-mask .modal .field").count()
            record("新增经销商弹窗打开且有表单字段", fields >= 2, "fields=%d" % fields)
            page.screenshot(path=str(OUT / "16-create-dealer-modal.png"), full_page=True)
        except Exception as e:
            record("新增经销商弹窗打开且有表单字段", False, str(e)[:120])
        close_overlays()

        # 编辑经销商弹窗（点第一行「编辑」）
        clear_collectors()
        try:
            edit_btn = page.locator("#view-dealers button", has_text="编辑").first
            if edit_btn.count() > 0:
                edit_btn.click()
                page.wait_for_selector(".modal-mask .modal", timeout=8000)
                title = page.locator(".modal-mask .modal header h3").inner_text()
                record("编辑经销商弹窗打开", len(title.strip()) > 0, title)
            else:
                record("编辑经销商弹窗打开", False, "没有可编辑的经销商行")
        except Exception as e:
            record("编辑经销商弹窗打开", False, str(e)[:120])
        close_overlays()

        # 名下车辆抽屉
        clear_collectors()
        try:
            vbtn = page.locator("#view-dealers button", has_text="名下车辆").first
            if vbtn.count() > 0:
                vbtn.click()
                page.wait_for_selector(".drawer.open", timeout=8000)
                dtitle = page.locator(".drawer header h3").first.inner_text()
                record("名下车辆抽屉打开", len(dtitle.strip()) > 0, dtitle)
                page.screenshot(path=str(OUT / "17-dealer-vehicles-drawer.png"), full_page=True)
            else:
                record("名下车辆抽屉打开", False, "没有「名下车辆」按钮")
        except Exception as e:
            record("名下车辆抽屉打开", False, str(e)[:120])
        close_overlays()

        # 车辆详情抽屉
        page.evaluate("location.hash = '#/vehicles'")
        page.wait_for_function(
            "() => { const v=document.getElementById('view-vehicles'); "
            "return v && v.classList.contains('active') && !v.querySelector('.loading'); }",
            timeout=15000)
        clear_collectors()
        try:
            detail_btn = page.locator("#view-vehicles button", has_text="详情").first
            if detail_btn.count() > 0:
                detail_btn.click()
                page.wait_for_selector(".drawer.open", timeout=8000)
                dtext = page.locator(".drawer .body").first.inner_text()
                record("车辆详情抽屉打开且有内容", len(dtext.strip()) > 10, dtext[:80])
                page.screenshot(path=str(OUT / "18-vehicle-detail-drawer.png"), full_page=True)
            else:
                record("车辆详情抽屉打开且有内容", False, "没有「详情」按钮")
        except Exception as e:
            record("车辆详情抽屉打开且有内容", False, str(e)[:120])
        close_overlays()

        # 交互期间不应有 JS 异常
        record("交互弹窗/抽屉无 JS 异常", not page_errors, " | ".join(page_errors[:3])[:200])

        # ---------- 5. 退出登录 ----------
        page.on("dialog", lambda d: d.accept())
        clear_collectors()
        page.click("#logoutBtn")
        try:
            page.wait_for_url("**/login.html", timeout=10000)
            record("退出登录跳转回登录页", True)
        except Exception as e:
            record("退出登录跳转回登录页", False, str(e)[:120])
        left_token = page.evaluate("localStorage.getItem('fp_token')")
        record("退出后令牌被清除", not left_token)

        # ---------- 6. operator 权限 ----------
        ok = login(page, "operator", "operator123")
        record("operator 登录成功", ok)
        if ok:
            page.wait_for_selector("#nav .nav-item", timeout=10000)
            op_role = page.locator("#roleChip").inner_text()
            record("operator 角色徽章渲染", len(op_role.strip()) > 0, op_role)
            page.evaluate("location.hash = '#/dealers'")
            page.wait_for_function(
                "() => { const v=document.getElementById('view-dealers'); "
                "return v && v.classList.contains('active') && !v.querySelector('.loading'); }",
                timeout=15000)
            op_buttons = page.locator("#view-dealers button").all_inner_texts()
            op_has_create = any("新增经销商" in b for b in op_buttons)
            record("operator 无「新增经销商」写按钮", not op_has_create, str(op_buttons[:6]))
            page.screenshot(path=str(OUT / "15-operator-dealers.png"), full_page=True)

        # ---------- 7. 直连 API 冒烟（浏览器内 fetch，带令牌） ----------
        clear_collectors()
        check = page.evaluate("""async () => {
          const t = localStorage.getItem('fp_token');
          const H = {'Authorization':'Bearer '+t};
          const out = [];
          const req = async (p) => {
            try {
              const r = await fetch(p, {headers:H});
              const b = await r.json().catch(()=>({}));
              out.push({path:p, status:r.status, code:b.code});
            } catch(e) { out.push({path:p, error:String(e)}); }
          };
          await req('/api/dealers');
          const dealers = await (await fetch('/api/dealers', {headers:H})).json().catch(()=>({data:[]}));
          const firstId = (dealers.data && dealers.data[0] && dealers.data[0].id) || 1;
          await req('/api/vehicles?dealerId=' + firstId);
          await req('/api/credit-lines?dealerId=' + firstId);
          await req('/api/alerts?limit=5');
          // 缺必填参数：应 400 VALID_001，不得 500 SYS_001
          await req('/api/vehicles');
          await req('/api/credit-lines');
          return {out, firstId};
        }""")
        for item in check["out"]:
            p = item["path"]
            if "?" in p:  # 带参的正常请求
                ok = item.get("status") == 200 and item.get("code") == "OK"
            elif p in ("/api/vehicles", "/api/credit-lines"):  # 缺参：应 400 VALID_001
                ok = item.get("status") == 400 and item.get("code") == "VALID_001"
                record("缺参 API %s 返回 400 而非 500" % p, ok, json.dumps(item, ensure_ascii=False))
                continue
            else:
                ok = item.get("status") == 200 and item.get("code") == "OK"
            record("API %s" % p, ok, json.dumps(item, ensure_ascii=False))

        browser.close()

    # ---------- 汇总 ----------
    total = len(results)
    failed = [r for r in results if not r["pass"]]
    report = {
        "time": datetime.now().isoformat(),
        "base": BASE,
        "total": total,
        "pass": total - len(failed),
        "fail": len(failed),
        "console_errors": console_errors[:50],
        "page_errors": page_errors[:50],
        "bad_responses": bad_responses[:50],
        "request_failures": request_failures[:50],
        "cases": results,
    }
    with open(OUT / ("browser-test-report-%s.json" % started), "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print("\n==== 汇总: %d PASS / %d FAIL (共 %d) ====" % (total - len(failed), len(failed), total), flush=True)
    for r in failed:
        print("  FAIL %s :: %s" % (r["case"], r["detail"][:200]), flush=True)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    run()
