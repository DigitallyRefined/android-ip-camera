import type {ReactNode} from 'react';
import clsx from 'clsx';
import Link from '@docusaurus/Link';
import useBaseUrl from '@docusaurus/useBaseUrl';
import Layout from '@theme/Layout';
import Heading from '@theme/Heading';
import {Icon, type IconName} from '@site/src/components/Icons';
import styles from './index.module.css';

const repoUrl = 'https://github.com/DigitallyRefined/android-ip-camera';
const releasesUrl = `${repoUrl}/releases`;
const fDroidUrl =
  'https://f-droid.org/en/packages/com.github.digitallyrefined.androidipcamera/';

type Feature = {
  icon: IconName;
  title: string;
  description: string;
  href: string;
  linkLabel: string;
};

const features: Feature[] = [
  {
    icon: 'camera',
    title: 'Live in any browser',
    description:
      'The phone runs its own web server. Watch H.264/MJPEG in any browser, listen to live audio and use push-to-talk - no viewer app required.',
    href: '/docs/getting-started#features',
    linkLabel: 'See the feature list',
  },
  {
    icon: 'broadcast',
    title: 'RTSP & ONVIF',
    description:
      'Serve a standards-based H.264/AAC stream and ONVIF camera profiles to Frigate, UniFi Protect, Home Assistant and NVRs.',
    href: '/docs/remote-access',
    linkLabel: 'Streaming options',
  },
  {
    icon: 'motion',
    title: 'Motion detection',
    description:
      'On-demand or always-on camera, automatic motion clips or continuous recording, with SD card storage and automatic cleanup of old footage.',
    href: '/docs/server-api',
    linkLabel: 'Recording API',
  },
  {
    icon: 'sliders',
    title: 'Remote controls',
    description:
      'Zoom, rotate, crop, switch lenses, toggle the torch and tune resolution, exposure, contrast and frame rate from the web UI or HTTP API.',
    href: '/docs/server-api',
    linkLabel: 'Control reference',
  },
  {
    icon: 'shield',
    title: 'Encrypted & authenticated',
    description:
      'Username and password protection, plus automatic or custom TLS shared by the web, RTSP and ONVIF endpoints.',
    href: '/docs/tls-certificates',
    linkLabel: 'TLS setup',
  },
  {
    icon: 'globe',
    title: 'Remote access',
    description:
      'Optionally publish the camera through a hosted or self-hosted OpenTunnel server, without opening a port on your router.',
    href: '/docs/remote-access',
    linkLabel: 'How OpenTunnel works',
  },
];

const protocols = [
  {icon: 'camera' as IconName, label: 'MJPEG + H.264'},
  {icon: 'broadcast' as IconName, label: 'RTSP / RTSPS'},
  {icon: 'motion' as IconName, label: 'ONVIF Profile S'},
  {icon: 'globe' as IconName, label: 'OpenTunnel ready'},
];

function Hero(): ReactNode {
  return (
    <header className={styles.hero}>
      <div className={styles.heroGrid} aria-hidden="true" />
      <div className={styles.heroGlowPrimary} aria-hidden="true" />
      <div className={styles.heroGlowAccent} aria-hidden="true" />
      <div className={clsx('container', styles.heroInner)}>
        <div className={styles.heroCopy}>
          <img
            className={styles.heroLogo}
            src={useBaseUrl('/icon.png')}
            alt="Android IP Camera app icon"
            width={84}
            height={84}
          />
          <p className={styles.heroEyebrow}>
            Open source · Android · Self-hosted
          </p>
          <Heading as="h1" className={styles.heroTitle}>
            Your phone,
            <br />
            <span className={styles.heroTitleAccent}>as an IP camera.</span>
          </Heading>
          <p className={styles.heroSubtitle}>
            Turn any Android device into a remote security camera with a
            built-in web server, RTSP and ONVIF support, motion recording and
            optional remote access.
          </p>
          <div className={styles.heroActions}>
            <Link
              className={clsx('button button--primary button--lg', styles.ctaButton)}
              to="/docs/getting-started">
              Get started
            </Link>
            <Link
              className={clsx('button button--lg', styles.secondaryButton)}
              href={releasesUrl}>
              Download
            </Link>
            <Link
              className={clsx('button button--lg', styles.ghostButton)}
              href={fDroidUrl}>
              F-Droid
            </Link>
          </div>
          <ul className={styles.heroChecks}>
            <li>
              <Icon name="check" />
              No account
            </li>
            <li>
              <Icon name="check" />
              Video stays on your network
            </li>
            <li>
              <Icon name="check" />
              Reproducible builds
            </li>
          </ul>
        </div>
        <div className={styles.heroVisual}>
          <div className={styles.browserFrame}>
            <div className={styles.browserBar}>
              <span className={styles.browserDot} />
              <span className={styles.browserDot} />
              <span className={styles.browserDot} />
              <span className={styles.browserAddress}>
                https://phone.local:4444
              </span>
              <span className={styles.liveBadge}>
                <span className={styles.liveDot} />
                Live
              </span>
            </div>
            <img
              className={styles.heroScreenshot}
              src={useBaseUrl('/screenshot.webp')}
              alt="Android IP Camera live stream and browser controls"
              width={2125}
              height={1169}
              loading="eager"
            />
          </div>
          <div className={styles.heroBadge}>
            <Icon name="shield" />
            <div>
              <strong>Secure by default</strong>
              <span>TLS password protected:</span>
              <span>Web · RTSP · ONVIF streams</span>
            </div>
          </div>
        </div>
      </div>
    </header>
  );
}

function ProtocolStrip(): ReactNode {
  return (
    <section className={styles.protocolStrip} aria-label="Supported protocols">
      <div className={clsx('container', styles.protocolInner)}>
        {protocols.map((protocol) => (
          <div className={styles.protocolItem} key={protocol.label}>
            <Icon name={protocol.icon} />
            <span>{protocol.label}</span>
          </div>
        ))}
      </div>
    </section>
  );
}

function Features(): ReactNode {
  return (
    <section className={styles.featuresSection}>
      <div className="container">
        <div className={styles.sectionHeading}>
          <p className={styles.eyebrow}>Everything in one app</p>
          <Heading as="h2">A complete camera stack in your pocket</Heading>
          <p className={styles.sectionLead}>
            Streaming, recording, integrations and remote access run on the
            phone. Nothing is uploaded to a cloud service unless you choose to
            enable it.
          </p>
        </div>
        <div className={styles.featureGrid}>
          {features.map((feature) => (
            <Link
              className={styles.featureCard}
              to={feature.href}
              key={feature.title}>
              <span className={styles.featureIcon}>
                <Icon name={feature.icon} />
              </span>
              <Heading as="h3">{feature.title}</Heading>
              <p>{feature.description}</p>
              <span className={styles.featureLink}>{feature.linkLabel} →</span>
            </Link>
          ))}
        </div>
      </div>
    </section>
  );
}

function Showcase(): ReactNode {
  return (
    <section className={styles.showcaseSection}>
      <div className="container">
        <div className={styles.showcaseRow}>
          <div className={styles.showcaseCopy}>
            <p className={styles.eyebrow}>Watch from anywhere</p>
            <Heading as="h2">Any browser. Any screen.</Heading>
            <p>
              The camera serves a responsive control panel and a live MJPEG or
              H.264 stream directly from the phone. Watch it on a laptop,
              tablet or TV, or point your existing NVR at the RTSP endpoint.
            </p>
            <ul className={styles.checkList}>
              <li>
                <Icon name="check" />
                Live preview, snapshots and recordings in one page
              </li>
              <li>
                <Icon name="check" />
                Push-to-talk, audio and torch controls
              </li>
              <li>
                <Icon name="check" />
                Works with Frigate, Home Assistant, UniFi Protect and mpv
              </li>
            </ul>
            <Link className="button button--primary" to="/docs/getting-started">
              Explore the documentation
            </Link>
          </div>
          <div className={styles.phoneStage}>
            <div className={styles.phoneFrame}>
              <img
                className={styles.phoneScreenshot}
                src={useBaseUrl('/phoneScreenshots/1.png')}
                alt="Android IP Camera live preview on a phone"
                width={540}
                height={1200}
                loading="lazy"
              />
            </div>
          </div>
        </div>

        <div className={clsx(styles.showcaseRow, styles.showcaseRowReverse)}>
          <div className={styles.desktopStage}>
            <div className={styles.desktopFrame}>
              <img
                className={styles.desktopScreenshot}
                src={useBaseUrl('/phoneScreenshots/3.png')}
                alt="Android IP Camera web control panel on a desktop browser"
                width={1045}
                height={1169}
                loading="lazy"
              />
            </div>
          </div>
          <div className={styles.showcaseCopy}>
            <p className={styles.eyebrow}>Full remote control</p>
            <Heading as="h2">Tune the shot without touching the phone.</Heading>
            <p>
              Switch between the main and selfie camera, crop the live image,
              adjust zoom and exposure, rotate or mirror the stream and record
              a clip - all from the browser.
            </p>
            <ul className={styles.checkList}>
              <li>
                <Icon name="check" />
                Resolution, frame rate and bitrate controls
              </li>
              <li>
                <Icon name="check" />
                Night-sight and automatic night mode on supported cameras
              </li>
              <li>
                <Icon name="check" />
                Automatic or custom TLS for HTTPS, RTSPS and ONVIF
              </li>
            </ul>
            <Link className="button button--primary" to="/docs/server-api">
              View the control reference
            </Link>
          </div>
        </div>
      </div>
    </section>
  );
}

function WarningCallout(): ReactNode {
  return (
    <section className={styles.calloutSection}>
      <div className={clsx('container', styles.callout)}>
        <span className={styles.calloutIcon}>
          <Icon name="battery" />
        </span>
        <div className={styles.calloutCopy}>
          <Heading as="h2">Built for 24/7</Heading>
          <p>
            Leaving a phone plugged in at 100% for months can damage the
            battery. Use the 80% charge limit where available, keep an eye on
            temperature and use the automatic pause at a battery temperature
            threshold (45 °C by default). The documentation covers background
            operation and automatic updates.
          </p>
        </div>
        <Link className="button button--secondary" to="/docs/getting-started#warning">
          Read the warning
        </Link>
      </div>
    </section>
  );
}

function FinalCta(): ReactNode {
  return (
    <section className={styles.finalCta}>
      <div className={styles.finalCtaGlow} aria-hidden="true" />
      <div className={clsx('container', styles.finalCtaInner)}>
        <img
          src={useBaseUrl('/icon.png')}
          alt=""
          width={56}
          height={56}
          className={styles.finalCtaLogo}
        />
        <Heading as="h2">Ready to put an old phone to work?</Heading>
        <p>
          Install Android IP Camera from F-Droid, Obtainium or GitHub Releases,
          then open the address shown on the phone in your browser.
        </p>
        <div className={styles.finalCtaActions}>
          <Link
            className={clsx('button button--primary button--lg', styles.ctaButton)}
            to="/docs/getting-started#install">
            Installation guide
          </Link>
          <Link
            className={clsx('button button--lg', styles.secondaryButton)}
            href={repoUrl}>
            View on GitHub
          </Link>
        </div>
      </div>
    </section>
  );
}

export default function Home(): ReactNode {
  return (
    <Layout
      description="Turn your Android device into a remote security camera with a built-in web server, RTSP, ONVIF, motion recording and optional remote access.">
      <Hero />
      <main>
        <ProtocolStrip />
        <Features />
        <Showcase />
        <WarningCallout />
        <FinalCta />
      </main>
    </Layout>
  );
}
