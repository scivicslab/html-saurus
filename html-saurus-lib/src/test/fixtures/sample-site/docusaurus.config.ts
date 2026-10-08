import type {Config} from '@docusaurus/types';

const config: Config = {
  title: 'Sample Site',
  favicon: 'img/favicon.ico',
  url: 'https://example.com',
  baseUrl: '/',
  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },
  presets: [['classic', {docs: {sidebarPath: './sidebars.ts'}, blog: {}}]],
  themeConfig: {
    navbar: {
      title: 'Sample Site',
      items: [
        {type: 'docSidebar', sidebarId: 'mainSidebar', position: 'left', label: 'Docs'},
        {to: '/blog', label: 'Blog', position: 'left'},
      ],
    },
  },
};

export default config;
