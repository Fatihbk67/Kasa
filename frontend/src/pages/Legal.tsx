import { Link } from 'react-router-dom';
import { Brand, usePageTitle } from '../components';
import { LanguagePicker, t, tk } from '../i18n';

const CONTACT = 'fatihbk67@gmail.com';
const UPDATED = '2026-11-05';

interface Section {
  title: string;
  paragraphs: string[];
}

// Turkish keys, translated where shown. Kept plain, because they are read by people deciding whether to trust the site.
const PRIVACY: Section[] = [
  {
    title: tk('Bu site nedir?'),
    paragraphs: [
      tk('Kasa, bir yazılım mühendisliği öğrencisinin portföy projesi olarak geliştirdiği bir ödeme altyapısı demosudur. Gerçek bir ödeme kuruluşu değildir ve gerçek para işlemez.'),
    ],
  },
  {
    title: tk('Hangi bilgileri topluyoruz?'),
    paragraphs: [
      tk('Hesap: e-posta adresiniz, işletme adınız ve parolanızın geri döndürülemez özeti. Parolanın kendisi saklanmaz.'),
      tk('Google ile giriş: yalnızca Google\'ın paylaştığı e-posta adresiniz ve adınız. Google hesabınızdaki başka hiçbir bilgiye erişmeyiz.'),
      tk('İşletme başvurusu: girdiğiniz unvan, vergi veya kimlik numarası, adres, telefon ve IBAN. Bunlar yalnızca platform incelemesi için kullanılır.'),
      tk('Ödemeler: tutar, açıklama ve sonuç. Kart numarası bu siteye hiç gelmez; iyzico ile ödemede kart, iyzico\'nun kendi sayfasında girilir.'),
      tk('Güvenlik kayıtları: giriş denemeleri ve önemli işlemler, IP adresiyle birlikte denetim günlüğüne yazılır.'),
    ],
  },
  {
    title: tk('Bilgileri kimlerle paylaşıyoruz?'),
    paragraphs: [
      tk('Bilgiler satılmaz ve reklam için kullanılmaz. Siteyi çalıştırmak için şu hizmetler kullanılır: Render (sunucu), Neon (veritabanı, Frankfurt), Brevo (e-posta gönderimi), iyzico (test ortamında kartlı ödeme) ve Google (yalnızca Google ile giriş seçilirse).'),
    ],
  },
  {
    title: tk('Çerezler ve tarayıcı depolama'),
    paragraphs: [
      tk('Reklam veya takip çerezi kullanılmaz. Oturumunuz tarayıcı sekmesi kapanınca silinen depolamada tutulur; tema ve dil seçiminiz bu tarayıcıda hatırlanır.'),
    ],
  },
  {
    title: tk('Haklarınız'),
    paragraphs: [
      tk('Hesabınızdaki bilgilerin silinmesini veya bir kopyasını istemek için aşağıdaki adrese yazabilirsiniz. Ödeme kayıtları, kişiyle bağlantısı kaldırılarak saklanabilir.'),
    ],
  },
];

const TERMS: Section[] = [
  {
    title: tk('Demo hizmeti'),
    paragraphs: [
      tk('Kasa bir portföy demosudur. Gerçek ödeme almak, para saklamak veya ticari bir hizmet sunmak için kullanılamaz. Bütün ödemeler test ortamındadır.'),
    ],
  },
  {
    title: tk('Kullanım kuralları'),
    paragraphs: [
      tk('Gerçek kart bilgisi, başkasına ait kişisel veri veya başka yerde kullandığınız bir parola girmeyin. Siteyi bozmaya, başka kullanıcıların verisine erişmeye veya aşırı yük oluşturmaya çalışmayın.'),
    ],
  },
  {
    title: tk('Sorumluluk'),
    paragraphs: [
      tk('Site olduğu gibi sunulur. Kesintisiz çalışacağı veya verilerin kalıcı olacağı garanti edilmez; demo verileri zaman zaman silinebilir.'),
    ],
  },
  {
    title: tk('Değişiklikler'),
    paragraphs: [
      tk('Bu koşullar ve gizlilik politikası güncellenebilir. Güncel hali her zaman bu sayfadadır.'),
    ],
  },
];

function LegalPage({ title, sections }: { title: string; sections: Section[] }) {
  usePageTitle(title);
  return (
    <div className="legal-page">
      <div className="legal-top">
        <Link to="/login" className="legal-brand"><Brand /></Link>
        <LanguagePicker compact />
      </div>
      <article className="card legal">
        <h1>{title}</h1>
        <p className="hint">{t('Son güncelleme: {date}', { date: UPDATED })}</p>
        {sections.map((section) => (
          <section key={section.title}>
            <h2>{t(section.title)}</h2>
            {section.paragraphs.map((paragraph) => <p key={paragraph}>{t(paragraph)}</p>)}
          </section>
        ))}
        <section>
          <h2>{t('İletişim')}</h2>
          <p>{t('Sorularınız için:')} <a href={`mailto:${CONTACT}`}>{CONTACT}</a></p>
        </section>
      </article>
      <LegalLinks />
    </div>
  );
}

/** Small links shown under the sign-in, sign-up and legal pages. */
export function LegalLinks() {
  return (
    <p className="legal-links muted">
      <Link to="/privacy">{t('Gizlilik politikası')}</Link>
      <span aria-hidden="true">·</span>
      <Link to="/terms">{t('Kullanım koşulları')}</Link>
    </p>
  );
}

export function PrivacyPage() {
  return <LegalPage title={t('Gizlilik politikası')} sections={PRIVACY} />;
}

export function TermsPage() {
  return <LegalPage title={t('Kullanım koşulları')} sections={TERMS} />;
}
