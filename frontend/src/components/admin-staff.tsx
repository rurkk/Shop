"use client";

import { useState } from "react";
import { write } from "@/lib/api";
import { useResource, useTask } from "@/lib/hooks";
import { Failure, Modal, Notice, Pending } from "./ui";

type Account = { id: string; email: string; canManageStaff: boolean };
type AccountList = { currentEmail: string; items: Account[] };
type Setup = { account: Account; totpSecret: string };
type Change = { account: Account; action: "reset" | "remove" };

const path = "/api/staff/accounts";

export function AdminStaff() {
  const accounts = useResource<AccountList>(path);
  const directory = accounts.value;
  const task = useTask();
  const [setup, setSetup] = useState<Setup>();
  const [change, setChange] = useState<Change>();
  const [notice, setNotice] = useState("");
  const [copied, setCopied] = useState(false);
  const changeTitle = change?.action === "reset" ? "Перевыпустить ключ?" : "Отозвать доступ?";

  function applyChange({ account, action }: Change) {
    setNotice("");
    void task.run(async () => {
      if (action === "remove") {
        await write(`${path}/${account.id}`, "DELETE");
        setNotice(`Доступ для ${account.email} отозван.`);
      } else {
        setSetup(await write<Setup>(`${path}/${account.id}/reset-totp`, "POST"));
        setCopied(false);
      }
      setChange(undefined);
      accounts.reload();
    });
  }

  return <>
    <div className="admin-heading"><h1>Сотрудники</h1></div>
    {accounts.loading && <Pending />}
    <Failure error={accounts.error} retry={accounts.reload} />
    {!change && <Failure error={task.error} />}
    {notice && <Notice>{notice}</Notice>}
    {setup && <section className="panel stack" aria-label="Настройка аутентификатора">
      <h2>Ключ для приложения-аутентификатора</h2>
      <p className="break-all">{setup.account.email}</p>
      <p>Передайте ключ сотруднику по защищённому каналу. В приложении выберите TOTP с шестью цифрами и периодом 30 секунд.</p>
      <label className="field">Ключ TOTP
        <input type="text" value={setup.totpSecret} readOnly spellCheck={false} autoComplete="off" />
      </label>
      <div className="actions">
        <button type="button" className="button outline" disabled={task.busy}
          onClick={() => void task.run(async () => {
            await navigator.clipboard.writeText(setup.totpSecret);
            setCopied(true);
          })}>Скопировать ключ</button>
        <button type="button" className="text-link" disabled={task.busy} onClick={() => setSetup(undefined)}>Закрыть ключ</button>
      </div>
      {copied && <Notice>Ключ скопирован.</Notice>}
    </section>}
    {directory && <>
      {!setup && !change && <form className="panel stack" onSubmit={(event) => {
        event.preventDefault();
        const form = event.currentTarget;
        const data = new FormData(form);
        setNotice("");
        void task.run(async () => {
          setSetup(await write<Setup>(path, "POST", {
            email: String(data.get("email")).trim(),
            canManageStaff: data.has("canManageStaff"),
          }));
          form.reset();
          setCopied(false);
          accounts.reload();
        });
      }}>
        <h2>Добавить сотрудника</h2>
        <label className="field">Рабочий email
          <input type="email" name="email" maxLength={254} disabled={task.busy} required />
        </label>
        <label className="check-field">
          <input type="checkbox" name="canManageStaff" disabled={task.busy} />
          Разрешить управлять доступом сотрудников
        </label>
        <div className="actions"><button className="button solid" disabled={task.busy}>Создать учётную запись</button></div>
      </form>}
      <ul className="admin-list" aria-label="Список сотрудников">
        {directory.items.map((account) => {
          const own = account.email === directory.currentEmail;
          return <li key={account.id} className="admin-row">
            <div>
              <p><strong>{account.email}</strong>{own && " (вы)"}</p>
              <p className="muted">{account.canManageStaff ? "Администратор" : "Сотрудник"}</p>
            </div>
            {!own && <div className="actions">
              <button type="button" className="text-link" disabled={task.busy || !!setup}
                onClick={() => setChange({ account, action: "reset" })}>Перевыпустить ключ</button>
              <button type="button" className="text-link" disabled={task.busy || !!setup}
                onClick={() => setChange({ account, action: "remove" })}>Отозвать доступ</button>
            </div>}
          </li>;
        })}
      </ul>
    </>}
    {change && <Modal title={changeTitle} onClose={() => setChange(undefined)} closeDisabled={task.busy}>
      <div className="stack">
        <h2>{changeTitle}</h2>
        <p className="break-all">{change.account.email} выйдет из админки. {change.action === "reset" && "Для входа понадобится новый ключ."}</p>
        <Failure error={task.error} />
        <div className="actions">
          <button className="button solid" type="button" disabled={task.busy}
            onClick={() => applyChange(change)}>{change.action === "reset" ? "Перевыпустить" : "Отозвать"}</button>
          <button className="text-link" type="button" disabled={task.busy} onClick={() => setChange(undefined)}>Отмена</button>
        </div>
      </div>
    </Modal>}
  </>;
}
