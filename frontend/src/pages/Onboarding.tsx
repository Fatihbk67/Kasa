import { useEffect, useState, type FormEvent } from 'react';
import { api, ApiError } from '../api';
import { ErrorNote, formatDateTime, Loading, PageHeader } from '../components';
import { formatIban, isValidTckn, isValidTrIban, isValidVkn, normalizeIban } from '../identifiers';
import { isOwner, useMe } from '../me';
import { useToast } from '../toast';
import type { BusinessProfile, BusinessType, Me, Sector } from '../types';
import { useFetch } from '../useFetch';

export const BUSINESS_TYPES: Record<BusinessType, { label: string; hint: string }> = {
  INDIVIDUAL: { label: 'Bireysel', hint: 'Şirketi olmayan, ara sıra satış yapan kişi' },
  SOLE_PROPRIETOR: { label: 'Şahıs şirketi', hint: 'Vergi mükellefi olan tek kişilik işletme' },
  COMPANY: { label: 'Limited veya anonim şirket', hint: 'Ticaret siciline kayıtlı tüzel kişi' },
};

export const SECTORS: Record<Sector, string> = {
  ECOMMERCE: 'E-ticaret',
  FOOD: 'Yiyecek ve içecek',
  EDUCATION: 'Eğitim',
  SERVICES: 'Hizmet',
  RETAIL: 'Perakende mağaza',
  SOFTWARE: 'Yazılım ve dijital ürün',
  OTHER: 'Diğer',
};

const BANKS: Record<string, string> = {
  '00010': 'Ziraat Bankası',
  '00012': 'Halkbank',
  '00015': 'VakıfBank',
  '00046': 'Akbank',
  '00062': 'Garanti BBVA',
  '00064': 'İş Bankası',
  '00067': 'Yapı Kredi',
  '00111': 'QNB',
  '00134': 'DenizBank',
  '00205': 'Kuveyt Türk',
};

export function bankName(code: string): string {
  return BANKS[code] ?? `Banka kodu ${code}`;
}

interface Form {
  businessType: BusinessType;
  legalName: string;
  taxId: string;
  taxOffice: string;
  sector: Sector;
  website: string;
  phone: string;
  address: string;
  city: string;
  iban: string;
}

const EMPTY: Form = {
  businessType: 'SOLE_PROPRIETOR', legalName: '', taxId: '', taxOffice: '', sector: 'ECOMMERCE',
  website: '', phone: '', address: '', city: '', iban: '',
};

function taxIdError(form: Form): string | null {
  if (form.taxId === '') {
    return null;
  }
  return form.businessType === 'COMPANY'
    ? (isValidVkn(form.taxId) ? null : 'Vergi kimlik numarası 10 haneli olmalı ve kontrol hanesi tutmalı.')
    : (isValidTckn(form.taxId) ? null : 'T.C. kimlik numarası 11 haneli olmalı ve kontrol haneleri tutmalı.');
}

function ibanError(iban: string): string | null {
  if (normalizeIban(iban) === '') {
    return null;
  }
  return isValidTrIban(iban) ? null : 'Geçerli bir TR IBAN girin. Rakamlardan biri yanlış yazılmış olabilir.';
}

/** Where the application stands, as a short row of steps the person can follow. */
function Steps({ me, profile }: { me: Me | null; profile: BusinessProfile | null }) {
  const approved = me?.tenantStatus === 'ACTIVE';
  const steps = [
    { label: 'Hesap açıldı', done: true },
    { label: 'Başvuru gönderildi', done: profile !== null },
    { label: 'Platform incelemesi', done: approved },
    { label: 'Ödeme almaya hazır', done: approved },
  ];
  return (
    <ol className="steps" aria-label="Başvuru adımları">
      {steps.map((step, index) => (
        <li key={step.label} className={step.done ? 'step step-done' : 'step'}>
          <span className="step-mark" aria-hidden="true">{step.done ? '✓' : index + 1}</span>
          <span>{step.label}</span>
          <span className="visually-hidden">{step.done ? ' (tamamlandı)' : ' (bekliyor)'}</span>
        </li>
      ))}
    </ol>
  );
}

function ProfileSummary({ profile }: { profile: BusinessProfile }) {
  return (
    <dl className="details">
      <div><dt>İşletme türü</dt><dd>{BUSINESS_TYPES[profile.businessType].label}</dd></div>
      <div><dt>Yasal unvan</dt><dd>{profile.legalName}</dd></div>
      <div><dt>{profile.businessType === 'COMPANY' ? 'Vergi kimlik no' : 'T.C. kimlik no'}</dt><dd>{profile.taxId}</dd></div>
      <div><dt>Vergi dairesi</dt><dd>{profile.taxOffice}</dd></div>
      <div><dt>Sektör</dt><dd>{SECTORS[profile.sector]}</dd></div>
      <div><dt>Telefon</dt><dd>{profile.phone}</dd></div>
      <div><dt>Web sitesi</dt><dd>{profile.website ?? '—'}</dd></div>
      <div><dt>Şehir</dt><dd>{profile.city}</dd></div>
      <div className="details-id"><dt>Adres</dt><dd>{profile.address}</dd></div>
      <div className="details-id"><dt>Ödeme hesabı</dt><dd>{bankName(profile.bankCode)} · <code>{profile.iban}</code></dd></div>
    </dl>
  );
}

export function Onboarding() {
  const { me, reload: reloadMe } = useMe();
  const notify = useToast();
  const profile = useFetch(() => api<BusinessProfile | undefined>('/settings/business-profile').then((found) => found ?? null), []);
  const [form, setForm] = useState<Form>(EMPTY);
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [touched, setTouched] = useState(false);

  const saved = profile.data ?? null;

  // An existing application is shown first; its IBAN comes back masked, so a correction asks for it again.
  useEffect(() => {
    if (saved) {
      setForm({ ...saved, website: saved.website ?? '', iban: '' });
    }
  }, [saved]);

  if (!isOwner(me)) {
    return (
      <>
        <PageHeader title="İşletme başvurusu" />
        <p className="note note-warning">Başvuruyu yalnızca işletme sahibi görebilir ve gönderebilir.</p>
      </>
    );
  }

  const set = <K extends keyof Form>(key: K, value: Form[K]) => setForm((current) => ({ ...current, [key]: value }));
  const taxError = taxIdError(form);
  const accountError = ibanError(form.iban);
  const showForm = saved === null || editing;
  const iban = normalizeIban(form.iban);
  const bank = isValidTrIban(iban) ? bankName(iban.slice(4, 9)) : null;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setTouched(true);
    if (taxError || accountError || !form.taxId || !iban) {
      setError('Lütfen işaretli alanları düzeltin.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await api<BusinessProfile>('/settings/business-profile', {
        method: 'PUT',
        body: { ...form, iban, legalName: form.legalName.trim(), website: form.website.trim() || null },
      });
      notify(saved ? 'Başvurunuz güncellendi.' : 'Başvurunuz incelemeye gönderildi.');
      setEditing(false);
      profile.reload();
      reloadMe();
    } catch (e) {
      setError(e instanceof ApiError && e.status === 403
        ? 'Başvuru göndermeden önce e-posta adresinizi doğrulayın. Bağlantıyı sayfanın üstünden yeniden isteyebilirsiniz.'
        : e instanceof ApiError && e.status === 409
        ? 'Onaylanmış bir başvuru değiştirilemez. Bilgileriniz değiştiyse destek ekibine yazın.'
        : e instanceof ApiError && e.status === 422
          ? 'Kimlik, vergi numarası veya IBAN doğrulanamadı. Lütfen kontrol edin.'
          : e instanceof Error ? e.message : 'Beklenmeyen bir hata oluştu.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageHeader title="İşletme başvurusu"
        description="Ödeme alabilmeniz için işletmenizi tanımamız gerekiyor. Bilgiler yalnızca platform incelemesi için kullanılır." />
      <Steps me={me} profile={saved} />
      <ErrorNote message={profile.error} />
      {profile.loading && !profile.data ? <Loading /> : profile.error ? null : (
        <div className="settings settings-wide">
          {saved && !editing && (
            <section className="card settings-card">
              <div className="section-header section-header-tight">
                <h2>Gönderilen bilgiler</h2>
                {saved.editable && <button type="button" className="button button-small" onClick={() => setEditing(true)}>Düzenle</button>}
              </div>
              {me?.tenantStatus === 'PENDING' && (
                <p className="note note-warning">Başvurunuz inceleniyor ({formatDateTime(saved.submittedAt)}). Sonuç size bildirim ve e-posta ile iletilecek.</p>
              )}
              {me?.tenantStatus === 'SUSPENDED' && (
                <p className="note note-danger">Hesabınız askıda. Gerekçeye göre bilgilerinizi düzeltip yeniden gönderebilirsiniz.</p>
              )}
              {me?.tenantStatus === 'ACTIVE' && (
                <p className="note note-success">Başvurunuz onaylandı. Güvenliğiniz için onaylı bilgiler, özellikle IBAN, panelden değiştirilemez.</p>
              )}
              <ProfileSummary profile={saved} />
            </section>
          )}

          {showForm && (
            <form className="stack" onSubmit={submit} noValidate={false}>
              <section className="card settings-card">
                <h2>İşletme türü</h2>
                <fieldset className="card-options">
                  <legend className="visually-hidden">İşletme türü</legend>
                  {(Object.keys(BUSINESS_TYPES) as BusinessType[]).map((type) => (
                    <label key={type} className="card-option">
                      <input type="radio" name="businessType" checked={form.businessType === type} onChange={() => set('businessType', type)} />
                      <span><strong>{BUSINESS_TYPES[type].label}</strong><br /><span className="hint">{BUSINESS_TYPES[type].hint}</span></span>
                    </label>
                  ))}
                </fieldset>
              </section>

              <section className="card settings-card stack">
                <h2>Yasal bilgiler</h2>
                <label className="field">
                  {form.businessType === 'COMPANY' ? 'Şirket unvanı' : 'Ad soyad veya işletme unvanı'}
                  <input required maxLength={200} value={form.legalName} onChange={(e) => set('legalName', e.target.value)}
                    placeholder={form.businessType === 'COMPANY' ? 'Örnek Gıda Ltd. Şti.' : 'Ayşe Yılmaz'} />
                </label>
                <div className="form-row">
                  <label className="field">
                    {form.businessType === 'COMPANY' ? 'Vergi kimlik numarası' : 'T.C. kimlik numarası'}
                    <input required inputMode="numeric" autoComplete="off" maxLength={form.businessType === 'COMPANY' ? 10 : 11}
                      value={form.taxId} aria-invalid={taxError !== null && touched}
                      onChange={(e) => set('taxId', e.target.value.replace(/\D/g, ''))} onBlur={() => setTouched(true)} />
                    {touched && taxError && <span className="field-error">{taxError}</span>}
                  </label>
                  <label className="field">
                    Vergi dairesi
                    <input required maxLength={100} value={form.taxOffice} onChange={(e) => set('taxOffice', e.target.value)} placeholder="Kadıköy" />
                  </label>
                </div>
                <label className="field">
                  Sektör
                  <select value={form.sector} onChange={(e) => set('sector', e.target.value as Sector)}>
                    {(Object.keys(SECTORS) as Sector[]).map((sector) => <option key={sector} value={sector}>{SECTORS[sector]}</option>)}
                  </select>
                </label>
              </section>

              <section className="card settings-card stack">
                <h2>İletişim</h2>
                <div className="form-row">
                  <label className="field">
                    Telefon
                    <input required type="tel" maxLength={30} autoComplete="tel" value={form.phone} onChange={(e) => set('phone', e.target.value)} placeholder="+90 5xx xxx xx xx" />
                  </label>
                  <label className="field">
                    Web sitesi <span className="hint">(isteğe bağlı)</span>
                    <input type="url" maxLength={200} value={form.website} onChange={(e) => set('website', e.target.value)} placeholder="https://" />
                  </label>
                </div>
                <label className="field">
                  Adres
                  <textarea required rows={2} maxLength={500} value={form.address} onChange={(e) => set('address', e.target.value)} />
                </label>
                <label className="field">
                  Şehir
                  <input required maxLength={100} value={form.city} onChange={(e) => set('city', e.target.value)} />
                </label>
              </section>

              <section className="card settings-card stack">
                <h2>Ödeme hesabı</h2>
                <p className="hint">Hesap kesimleri bu hesaba gönderilir. IBAN, yukarıdaki unvan adına kayıtlı olmalıdır.</p>
                <label className="field">
                  IBAN
                  <input required autoComplete="off" spellCheck={false} maxLength={40} value={form.iban}
                    aria-invalid={accountError !== null && touched} placeholder="TR00 0000 0000 0000 0000 0000 00"
                    onChange={(e) => set('iban', e.target.value)} onBlur={() => { setTouched(true); if (isValidTrIban(form.iban)) set('iban', formatIban(form.iban)); }} />
                  {touched && accountError && <span className="field-error">{accountError}</span>}
                  {bank && <span className="hint">✓ {bank}</span>}
                  {saved && <span className="hint">Güvenlik nedeniyle kayıtlı IBAN gösterilmez; düzenlerken yeniden girin.</span>}
                </label>
              </section>

              <ErrorNote message={error} />
              <div className="form-actions">
                {editing && <button type="button" className="button" onClick={() => { setEditing(false); setError(null); }} disabled={busy}>Vazgeç</button>}
                <button type="submit" className="button button-primary" disabled={busy || me?.emailVerified === false}
                  title={me?.emailVerified === false ? 'Önce e-posta adresinizi doğrulayın' : undefined}>
                  {busy ? 'Gönderiliyor…' : saved ? 'Güncellemeyi gönder' : 'Başvuruyu gönder'}
                </button>
              </div>
            </form>
          )}
        </div>
      )}
    </>
  );
}
