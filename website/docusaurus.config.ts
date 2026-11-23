import {themes as prismThemes} from 'prism-react-renderer';
import type {Config} from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';
import readmeDocsPlugin from './plugins/readme-docs';

// This runs in Node.js - Don't use client-side code here (browser APIs, JSX...)

const repoUrl = 'https://github.com/DigitallyRefined/android-ip-camera';

const obtainiumUrl =
  'https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.github.digitallyrefined.androidipcamera%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FDigitallyRefined%2Fandroid-ip-camera%22%2C%22author%22%3A%22DigitallyRefined%22%2C%22name%22%3A%22Android%20IP%20Camera%22%7D';

const fDroidUrl =
  'https://f-droid.org/en/packages/com.github.digitallyrefined.androidipcamera/';

const config: Config = {
  title: 'Android IP Camera',
  tagline: 'Turn your Android device into a remote security camera',
  favicon: 'icon.png',

  // Fastlane images are mounted into the container and served directly. No
  // image files need to be copied into website/static.
  staticDirectories: [
    'static',
    process.env.DOCUSAURUS_ASSETS_DIR ??
      '../fastlane/metadata/android/en-US/images',
  ],

  future: {
    v4: true,
  },

  // Override these for a different deployment.
  url: process.env.DOCUSAURUS_URL ?? 'https://ip-cam.app',
  baseUrl: process.env.DOCUSAURUS_BASE_URL ?? '/',

  organizationName: 'DigitallyRefined',
  projectName: 'android-ip-camera',

  onBrokenLinks: 'throw',

  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  presets: [
    [
      'classic',
      {
        docs: {
          // Generated from /README.md by plugins/readme-docs.
          sidebarPath: './docs/sidebars.js',
          routeBasePath: 'docs',
          breadcrumbs: true,
        },
        blog: false,
        theme: {
          customCss: './src/css/custom.css',
        },
      } satisfies Preset.Options,
    ],
  ],

  // The README is the only source of documentation. This plugin turns it into
  // the docs folder and its sidebar whenever the site starts or builds.
  plugins: [readmeDocsPlugin],

  themeConfig: {
    image: 'icon.png',
    metadata: [
      {
        name: 'keywords',
        content:
          'Android IP camera, IP camera, RTSP, ONVIF, MJPEG, security camera, self-hosted',
      },
      {name: 'theme-color', content: '#1a237e'},
    ],
    colorMode: {
      defaultMode: 'light',
      disableSwitch: false,
      // Follow the browser/system preference until the visitor picks a theme.
      respectPrefersColorScheme: true,
    },
    navbar: {
      title: 'Android IP Camera',
      logo: {
        alt: 'Android IP Camera logo',
        src: 'icon.png',
      },
      items: [
        {
          type: 'docSidebar',
          sidebarId: 'docs',
          position: 'left',
          label: 'Documentation',
        },
        {
          to: '/docs/getting-started#install',
          label: 'Install',
          position: 'left',
        },
        {
          to: '/docs/server-api',
          label: 'API',
          position: 'left',
        },
        {
          href: repoUrl,
          label: 'GitHub',
          position: 'right',
        },
        {
          href: fDroidUrl,
          label: 'F-Droid',
          position: 'right',
        },
      ],
    },
    footer: {
      style: 'dark',
      logo: {
        alt: 'Android IP Camera logo',
        src: 'icon.png',
        width: 44,
        height: 44,
      },
      links: [
        {
          title: 'Documentation',
          items: [
            {label: 'Getting Started', to: '/docs/getting-started'},
            {label: 'Install', to: '/docs/getting-started#install'},
            {label: 'Frigate Configuration', to: '/docs/frigate'},
            {label: 'Server API Reference', to: '/docs/server-api'},
          ],
        },
        {
          title: 'Project',
          items: [
            {label: 'GitHub', href: repoUrl},
            {label: 'Releases', href: `${repoUrl}/releases`},
            {label: 'Issues', href: `${repoUrl}/issues`},
            {
              label: 'Reproducible Builds',
              to: '/docs/reproducible-builds',
            },
          ],
        },
        {
          title: 'Get the app',
          items: [
            {label: 'Obtainium', href: obtainiumUrl},
            {
              label: 'GitHub Releases',
              href: `${repoUrl}/releases`,
            },
            {label: 'F-Droid', href: fDroidUrl},
          ],
        },
      ],
      copyright: `Copyright © ${new Date().getFullYear()} DigitallyRefined. Built with Docusaurus.`,
    },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.oneDark,
    },
    tableOfContents: {
      minHeadingLevel: 2,
      maxHeadingLevel: 4,
    },
    docs: {
      sidebar: {
        // Keep the desktop navigation permanently visible.
        hideable: false,
        autoCollapseCategories: false,
      },
    },
  } satisfies Preset.ThemeConfig,
};

export default config;
