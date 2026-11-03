import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api, ApiError } from '../api';
import { useAuth } from '../auth';
import { CopyButton, EmptyState, ErrorNote, FormDialog, formatDateTime, Loading, PageHeader } from '../components';
import { canOperate, isOwner, ROLE_LABELS, useMe } from '../me';
import type { PublicInvitation, Role, Team, TeamInvitation, TeamMember } from '../types';
import { useToast } from '../toast';
import { useFetch } from '../useFetch';

const ROLE_HELP: Record<Role, string> = {
  OWNER: 'Ekibi, ayarları ve her şeyi yönetir.',
  ADMIN: 'Ödemeleri işler, API anahtarlarını ve webhook\'ları yönetir.',
  VIEWER: 'Her şeyi görür, hiçbir şeyi değiştiremez.',
};

function friendlyTeamError(e: unknown): Error {
  if (e instanceof ApiError && e.status === 409) {
    return new Error('Bu e-postanın zaten açık bir daveti var.');
  }
  if (e instanceof ApiError && e.status === 403) {
    return new Error('Bu işlem için yetkiniz yok. Yalnızca sahipler üst rol verebilir.');
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
    <FormDialog title="Ekibe davet et" submitLabel="Davet oluştur" onClose={onClose} onSubmit={submit}>
      <label className="field">
        E-posta
        <input type="email" required autoFocus value={email} onChange={(e) => setEmail(e.target.value)} />
      </label>
      <label className="field">
        Rol
        <select value={role} onChange={(e) => setRole(e.target.value as Role)}>
          {roles.map((option) => <option key={option} value={option}>{ROLE_LABELS[option]}</option>)}
        </select>
        <span className="hint">{ROLE_HELP[role]}</span>
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
    <FormDialog title="Rolü değiştir" submitLabel="Kaydet" onClose={onClose} onSubmit={submit}>
      <p className="muted">{member.email}</p>
      <label className="field">
        Rol
        <select value={role} onChange={(e) => setRole(e.target.value as Role)}>
          {(['VIEWER', 'ADMIN', 'OWNER'] as Role[]).map((option) => <option key={option} value={option}>{ROLE_LABELS[option]}</option>)}
        </select>
        <span className="hint">{ROLE_HELP[role]} Değişiklik hemen geçerli olur.</span>
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
    notify('Üye çıkarıldı, oturumları kapatıldı.');
  };

  const revoke = async () => {
    await api(`/team/invitations/${revoking!.id}`, { method: 'DELETE' });
    setRevoking(null);
    team.reload();
    notify('Davet iptal edildi.');
  };

  return (
    <>
      <PageHeader
        title="Ekip"
        description="Kimin neye erişebileceğini yönetin."
        actions={canOperate(me) && <button type="button" className="button button-primary" onClick={() => setInviting(true)}>Davet et</button>}
      />
      {link && (
        <div className="note note-warning">
          <strong>Davet bağlantısı hazır</strong>
          <p>Bu bağlantıyı davet ettiğiniz kişiyle paylaşın. 7 gün geçerlidir, tek kullanımlıktır ve bir daha gösterilmeyecek.</p>
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
                  <th scope="col">Üye</th>
                  <th scope="col">Rol</th>
                  <th scope="col" className="hide-narrow">Katılma</th>
                  <th scope="col"><span className="visually-hidden">İşlemler</span></th>
                </tr>
              </thead>
              <tbody>
                {team.data.members.map((member) => (
                  <tr key={member.id}>
                    <td>
                      {member.email}
                      {member.id === me?.userId && <span className="hint row-hint">Siz</span>}
                    </td>
                    <td>
                      {member.disabledAt
                        ? <span className="badge badge-neutral">Çıkarıldı</span>
                        : <span className="badge badge-info">{ROLE_LABELS[member.role]}</span>}
                    </td>
                    <td className="hide-narrow muted">{formatDateTime(member.createdAt)}</td>
                    <td className="numeric">
                      {owner && !member.disabledAt && member.id !== me?.userId && (
                        <span className="row-actions">
                          <button type="button" className="button button-small" onClick={() => setEditing(member)}>Rolü değiştir</button>
                          <button type="button" className="button button-small" onClick={() => setRemoving(member)}>Çıkar</button>
                        </span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="section-header"><h2>Bekleyen davetler</h2></div>
          {team.data.invitations.length > 0 ? (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th scope="col">E-posta</th>
                    <th scope="col">Rol</th>
                    <th scope="col" className="hide-narrow">Son geçerlilik</th>
                    <th scope="col"><span className="visually-hidden">İşlemler</span></th>
                  </tr>
                </thead>
                <tbody>
                  {team.data.invitations.map((invitation) => (
                    <tr key={invitation.id}>
                      <td>{invitation.email}</td>
                      <td><span className="badge badge-neutral">{ROLE_LABELS[invitation.role]}</span></td>
                      <td className="hide-narrow muted">{formatDateTime(invitation.expiresAt)}</td>
                      <td className="numeric">
                        <button type="button" className="button button-small" onClick={() => setRevoking(invitation)}>İptal et</button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : <div className="card"><EmptyState title="Bekleyen davet yok" /></div>}
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
            notify('Rol değişti.');
          }}
        />
      )}
      {removing && (
        <FormDialog title="Üyeyi çıkar" submitLabel="Çıkar" danger onClose={() => setRemoving(null)} onSubmit={remove}>
          <p><strong>{removing.email}</strong> erişimini hemen kaybeder ve açık oturumları kapanır.</p>
        </FormDialog>
      )}
      {revoking && (
        <FormDialog title="Daveti iptal et" submitLabel="İptal et" danger onClose={() => setRevoking(null)} onSubmit={revoke}>
          <p><strong>{revoking.email}</strong> için oluşturulan bağlantı artık çalışmayacak.</p>
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
      setError(e instanceof ApiError && e.status === 409 ? 'Bu e-posta ile zaten bir hesap var.' : (e as Error).message);
      setBusy(false);
    }
  };

  const data = invitation.data;
  return (
    <div className="auth-page">
      <div className="card auth-card">
        <div className="brand">Kasa</div>
        {invitation.loading ? <Loading /> : !data ? (
          <>
            <h1>Davet bulunamadı</h1>
            <p className="muted">Bağlantıyı sizi davet eden kişiden yeniden isteyin.</p>
          </>
        ) : data.status !== 'OPEN' ? (
          <>
            <h1>Bu davet artık geçerli değil</h1>
            <p className="muted">Davet kullanılmış, iptal edilmiş veya süresi dolmuş olabilir. <Link to="/login">Giriş sayfasına git</Link></p>
          </>
        ) : (
          <>
            <h1>{data.tenantName} ekibine katılın</h1>
            <p className="muted">{data.email} için {ROLE_LABELS[data.role]} rolüyle davet edildiniz.</p>
            <p className="note note-warning auth-notice">
              Bu bir portföy demosudur. Başka yerde kullandığınız bir parola girmeyin.
            </p>
            <form onSubmit={submit} className="stack">
              <label className="field">
                Parola belirleyin
                <input type="password" required minLength={12} maxLength={72} autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} />
                <span className="hint">En az 12 karakter</span>
              </label>
              <ErrorNote message={error} />
              <button type="submit" className="button button-primary button-block" disabled={busy}>
                {busy ? 'Katılınıyor…' : 'Ekibe katıl'}
              </button>
            </form>
          </>
        )}
      </div>
    </div>
  );
}
