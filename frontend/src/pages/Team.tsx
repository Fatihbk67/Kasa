import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api, ApiError } from '../api';
import { useAuth } from '../auth';
import { Brand, CopyButton, EmptyState, ErrorNote, FormDialog, formatDateTime, Loading, PageHeader } from '../components';
import { LanguagePicker, t, tk } from '../i18n';
import { canOperate, isOwner, ROLE_LABELS, useMe } from '../me';
import type { PublicInvitation, Role, Team, TeamInvitation, TeamMember } from '../types';
import { useToast } from '../toast';
import { useFetch } from '../useFetch';

const ROLE_HELP: Record<Role, string> = {
  OWNER: tk('Ekibi, ayarları ve her şeyi yönetir.'),
  ADMIN: tk('Ödemeleri işler, API anahtarlarını ve webhook\'ları yönetir.'),
  VIEWER: tk('Her şeyi görür, hiçbir şeyi değiştiremez.'),
};

function friendlyTeamError(e: unknown): Error {
  if (e instanceof ApiError && e.status === 409) {
    return new Error(t('Bu e-postanın zaten açık bir daveti var.'));
  }
  if (e instanceof ApiError && e.status === 403) {
    return new Error(t('Bu işlem için yetkiniz yok. Yalnızca sahipler üst rol verebilir.'));
  }
  return e as Error;
}

function InviteDialog({ canGrantAll, onClose, onCreated }: { canGrantAll: boolean; onClose: () => void; onCreated: (link: string) => void }) {
  const [email, setEmail] = useState('');
  const [role, setRole] = useState<Role>('VIEWER');
  const roles: Role[] = canGrantAll ? ['VIEWER', 'ADMIN', 'OWNER'] : ['VIEWER'];

  const submit = async () => {
    try {
      const created = await api<{ token: string }>('/team/invitations', { method: 'POST', body: { email, role } });
      onCreated(`${window.location.origin}/invite/${created.token}`);
    } catch (e) {
      throw friendlyTeamError(e);
    }
  };

  return (
    <FormDialog title={t('Ekibe davet et')} submitLabel={t('Davet oluştur')} onClose={onClose} onSubmit={submit}>
      <label className="field">
        {t('E-posta')}
        <input type="email" required autoFocus value={email} onChange={(e) => setEmail(e.target.value)} />
      </label>
      <label className="field">
        {t('Rol')}
        <select value={role} onChange={(e) => setRole(e.target.value as Role)}>
          {roles.map((option) => <option key={option} value={option}>{t(ROLE_LABELS[option])}</option>)}
        </select>
        <span className="hint">{t(ROLE_HELP[role])}</span>
      </label>
    </FormDialog>
  );
}

function RoleDialog({ member, onClose, onDone }: { member: TeamMember; onClose: () => void; onDone: () => void }) {
  const [role, setRole] = useState<Role>(member.role);
  const submit = async () => {
    await api(`/team/members/${member.id}/role`, { method: 'PUT', body: { role } }).catch((e) => {
      throw friendlyTeamError(e);
    });
    onDone();
  };
  return (
    <FormDialog title={t('Rolü değiştir')} submitLabel={t('Kaydet')} onClose={onClose} onSubmit={submit}>
      <p className="muted">{member.email}</p>
      <label className="field">
        {t('Rol')}
        <select value={role} onChange={(e) => setRole(e.target.value as Role)}>
          {(['VIEWER', 'ADMIN', 'OWNER'] as Role[]).map((option) => <option key={option} value={option}>{t(ROLE_LABELS[option])}</option>)}
        </select>
        <span className="hint">{t(ROLE_HELP[role])} {t('Değişiklik hemen geçerli olur.')}</span>
      </label>
    </FormDialog>
  );
}

export function TeamPage() {
  const notify = useToast();
  const { me } = useMe();
  const team = useFetch(() => api<Team>('/team'), []);
  const [inviting, setInviting] = useState(false);
  const [link, setLink] = useState<string | null>(null);
  const [editing, setEditing] = useState<TeamMember | null>(null);
  const [removing, setRemoving] = useState<TeamMember | null>(null);
  const [revoking, setRevoking] = useState<TeamInvitation | null>(null);
  const owner = isOwner(me);

  const remove = async () => {
    await api(`/team/members/${removing!.id}`, { method: 'DELETE' });
    setRemoving(null);
    team.reload();
    notify(t('Üye çıkarıldı, oturumları kapatıldı.'));
  };

  const revoke = async () => {
    await api(`/team/invitations/${revoking!.id}`, { method: 'DELETE' });
    setRevoking(null);
    team.reload();
    notify(t('Davet iptal edildi.'));
  };

  return (
    <>
      <PageHeader
        title={t('Ekip')}
        description={t('Kimin neye erişebileceğini yönetin.')}
        actions={canOperate(me) && <button type="button" className="button button-primary" onClick={() => setInviting(true)}>{t('Davet et')}</button>}
      />
      {link && (
        <div className="note note-warning">
          <strong>{t('Davet bağlantısı hazır')}</strong>
          <p>{t('Bu bağlantıyı davet ettiğiniz kişiyle paylaşın. 7 gün geçerlidir, tek kullanımlıktır ve bir daha gösterilmeyecek.')}</p>
          <div className="secret-row">
            <code className="secret">{link}</code>
            <CopyButton value={link} />
          </div>
        </div>
      )}
      <ErrorNote message={team.error} />
      {team.loading ? <Loading /> : team.data && (
        <>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th scope="col">{t('Üye')}</th>
                  <th scope="col">{t('Rol')}</th>
                  <th scope="col" className="hide-narrow">{t('Katılma')}</th>
                  <th scope="col"><span className="visually-hidden">{t('İşlemler')}</span></th>
                </tr>
              </thead>
              <tbody>
                {team.data.members.map((member) => (
                  <tr key={member.id}>
                    <td>
                      {member.email}
                      {member.id === me?.userId && <span className="hint row-hint">{t('Siz')}</span>}
                    </td>
                    <td>
                      {member.disabledAt
                        ? <span className="badge badge-neutral">{t('Çıkarıldı')}</span>
                        : <span className="badge badge-info">{t(ROLE_LABELS[member.role])}</span>}
                    </td>
                    <td className="hide-narrow muted">{formatDateTime(member.createdAt)}</td>
                    <td className="numeric">
                      {owner && !member.disabledAt && member.id !== me?.userId && (
                        <span className="row-actions">
                          <button type="button" className="button button-small" onClick={() => setEditing(member)}>{t('Rolü değiştir')}</button>
                          <button type="button" className="button button-small" onClick={() => setRemoving(member)}>{t('Çıkar')}</button>
                        </span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="section-header"><h2>{t('Bekleyen davetler')}</h2></div>
          {team.data.invitations.length > 0 ? (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th scope="col">{t('E-posta')}</th>
                    <th scope="col">{t('Rol')}</th>
                    <th scope="col" className="hide-narrow">{t('Son geçerlilik')}</th>
                    <th scope="col"><span className="visually-hidden">{t('İşlemler')}</span></th>
                  </tr>
                </thead>
                <tbody>
                  {team.data.invitations.map((invitation) => (
                    <tr key={invitation.id}>
                      <td>{invitation.email}</td>
                      <td><span className="badge badge-neutral">{t(ROLE_LABELS[invitation.role])}</span></td>
                      <td className="hide-narrow muted">{formatDateTime(invitation.expiresAt)}</td>
                      <td className="numeric">
                        <button type="button" className="button button-small" onClick={() => setRevoking(invitation)}>{t('Daveti iptal et')}</button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : <div className="card"><EmptyState title={t('Bekleyen davet yok')} /></div>}
        </>
      )}

      {inviting && (
        <InviteDialog
          canGrantAll={owner}
          onClose={() => setInviting(false)}
          onCreated={(created) => {
            setLink(created);
            setInviting(false);
            team.reload();
          }}
        />
      )}
      {editing && (
        <RoleDialog
          member={editing}
          onClose={() => setEditing(null)}
          onDone={() => {
            setEditing(null);
            team.reload();
            notify(t('Rol değişti.'));
          }}
        />
      )}
      {removing && (
        <FormDialog title={t('Üyeyi çıkar')} submitLabel={t('Çıkar')} danger onClose={() => setRemoving(null)} onSubmit={remove}>
          <p>{t('{email} erişimini hemen kaybeder ve açık oturumları kapanır.', { email: removing.email })}</p>
        </FormDialog>
      )}
      {revoking && (
        <FormDialog title={t('Daveti iptal et')} submitLabel={t('Daveti iptal et')} danger onClose={() => setRevoking(null)} onSubmit={revoke}>
          <p>{t('{email} için oluşturulan bağlantı artık çalışmayacak.', { email: revoking.email })}</p>
        </FormDialog>
      )}
    </>
  );
}

export function AcceptInvitation() {
  const { token } = useParams();
  const navigate = useNavigate();
  const { signIn } = useAuth();
  const invitation = useFetch(() => api<PublicInvitation>(`/public/invitations/${token}`), [token]);
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const { accessToken } = await api<{ accessToken: string }>(`/public/invitations/${token}/accept`, { method: 'POST', body: { password } });
      signIn(accessToken);
      navigate('/');
    } catch (e) {
      setError(e instanceof ApiError && e.status === 409 ? t('Bu e-posta ile zaten bir hesap var.') : (e as Error).message);
      setBusy(false);
    }
  };

  const data = invitation.data;
  return (
    <div className="auth-page">
      <div className="card auth-card">
        <div className="auth-card-top">
          <Brand />
          <LanguagePicker compact />
        </div>
        {invitation.loading ? <Loading /> : !data ? (
          <>
            <h1>{t('Davet bulunamadı')}</h1>
            <p className="muted">{t('Bağlantıyı sizi davet eden kişiden yeniden isteyin.')}</p>
          </>
        ) : data.status !== 'OPEN' ? (
          <>
            <h1>{t('Bu davet artık geçerli değil')}</h1>
            <p className="muted">{t('Davet kullanılmış, iptal edilmiş veya süresi dolmuş olabilir.')} <Link to="/login">{t('Giriş sayfasına git')}</Link></p>
          </>
        ) : (
          <>
            <h1>{t('{team} ekibine katılın', { team: data.tenantName })}</h1>
            <p className="muted">{t('{email} için {role} rolüyle davet edildiniz.', { email: data.email, role: t(ROLE_LABELS[data.role]) })}</p>
            <p className="note note-warning auth-notice">
              {t('Bu bir portföy demosudur. Başka yerde kullandığınız bir parola girmeyin.')}
            </p>
            <form onSubmit={submit} className="stack">
              <label className="field">
                {t('Parola belirleyin')}
                <input type="password" required minLength={12} maxLength={72} autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} />
                <span className="hint">{t('En az 12 karakter')}</span>
              </label>
              <ErrorNote message={error} />
              <button type="submit" className="button button-primary button-block" disabled={busy}>
                {busy ? t('Katılınıyor…') : t('Ekibe katıl')}
              </button>
            </form>
          </>
        )}
      </div>
    </div>
  );
}
