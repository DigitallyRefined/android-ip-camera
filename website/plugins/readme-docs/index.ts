/**
 * Docusaurus plugin that turns the repository README into the documentation
 * site. The README stays the single source of truth: nothing is copied into
 * the repository by hand.
 *
 * The docs are written while the plugin is constructed, which happens before
 * any plugin's loadContent() runs, so the docs plugin always sees them.
 */

import fs from 'node:fs';
import path from 'node:path';

import type {LoadContext, Plugin} from '@docusaurus/types';

const REPO_URL = 'https://github.com/DigitallyRefined/android-ip-camera';
const DEFAULT_OUTPUT_DIR = 'docs';
const DEFAULT_SIDEBAR_FILE = 'sidebars.js';
const ROOT_SCREENSHOT = 'screenshot.webp';

type SectionMeta = {
  slug: string;
  title: string;
  category: string;
  direct?: boolean;
  description?: string;
};

/**
 * Quality titles, slugs and sidebar placement for the README headings we know
 * about. Any heading that is not listed still becomes a page, under "More".
 */
const SECTION_META: Record<string, SectionMeta> = {
  'Getting Started': {
    slug: 'getting-started',
    title: 'Getting Started',
    category: 'Getting Started',
    direct: true,
    description:
      'Overview, install options and the full feature list for Android IP Camera.',
  },
  Overview: {
    slug: 'overview',
    title: 'Overview',
    category: 'Getting Started',
    description:
      'Turn your Android device into a remote security IP camera with a built-in web server, RTSP, ONVIF and optional remote access.',
  },
  Install: {
    slug: 'install',
    title: 'Install',
    category: 'Getting Started',
    description:
      'Install Android IP Camera from GitHub Releases, F-Droid or Obtainium, then open the stream in any browser.',
  },
  Features: {
    slug: 'features',
    title: 'Features',
    category: 'Getting Started',
    description:
      'Built-in web server, MJPEG and H.264 streams, RTSP, ONVIF, motion recording, night mode, TLS and optional remote access.',
  },
  Warning: {
    slug: 'warning',
    title: 'Warning',
    category: 'Operating the Camera',
  },
  'Background Usage': {
    slug: 'background-usage',
    title: 'Background Usage',
    category: 'Operating the Camera',
  },
  'Frigate config': {
    slug: 'frigate',
    title: 'Frigate Configuration',
    category: 'Integrations',
    description:
      'Example Frigate and go2rtc configuration for the HTTPS and RTSP streams, including two-way talk audio.',
  },
  'Remote access with OpenTunnel': {
    slug: 'remote-access',
    title: 'Remote Access & Streaming',
    category: 'Integrations',
  },
  'HTTPS/TLS certificates': {
    slug: 'tls-certificates',
    title: 'HTTPS & TLS Certificates',
    category: 'Operating the Camera',
  },
  'Why open source': {
    slug: 'why-open-source',
    title: 'Why Open Source',
    category: 'Project',
  },
  'AI generated code disclaimer': {
    slug: 'ai-generated-code',
    title: 'AI-Generated Code',
    category: 'Project',
  },
  'Server URL Paths & Remote Control API': {
    slug: 'server-api',
    title: 'Server API Reference',
    category: 'Reference',
    description:
      'Stream URLs, recording endpoints, the recordings browser and every remote-control query parameter exposed by the camera server.',
  },
  Permissions: {
    slug: 'permissions',
    title: 'Permissions',
    category: 'Reference',
    description:
      'Android permissions used by Android IP Camera, and why each one is needed.',
  },
  'Reproducible builds': {
    slug: 'reproducible-builds',
    title: 'Reproducible Builds',
    category: 'Development',
    description:
      'Build release APKs in the Docker builder and verify them against the published F-Droid release.',
  },
};

const CATEGORY_ORDER = [
  'Getting Started',
  'Operating the Camera',
  'Integrations',
  'Reference',
  'Development',
  'Project',
  'More',
];

/**
 * README sections that should share one documentation page. The remaining
 * sections become one page each.
 */
const PAGE_GROUPS: Array<{label: string; members: string[]}> = [
  {
    label: 'Getting Started',
    members: ['Overview', 'Install', 'Features', 'Warning'],
  },
];

type Section = {
  label: string | null;
  lines: string[];
};

type Detail = {
  label: string;
  body: string;
};

type Block = {
  kind: 'section' | 'detail';
  label: string;
  body: string;
};

type Doc = {
  slug: string;
  title: string;
  category: string;
  direct: boolean;
  order: number;
  content: string;
};

type SidebarCategory = {
  type: 'category';
  label: string;
  collapsible: boolean;
  collapsed: boolean;
  items: string[];
};

export type ReadmeDocsPluginOptions = {
  readmePath?: string;
  outputDir?: string;
  sidebarFile?: string;
};

/** Remove leading emoji/decorative characters from a README heading. */
function normalizeHeading(rawHeading: string): string {
  return String(rawHeading)
    .replace(/<[^>]+>/g, '')
    .replace(/^[^\p{L}\p{N}]+/u, '')
    .replace(/\s+/g, ' ')
    .trim();
}

function slugify(value: string): string {
  return (
    value
      .toLowerCase()
      .normalize('NFKD')
      .replace(/[^\w\s-]/g, '')
      .trim()
      .replace(/[\s_]+/g, '-')
      .replace(/-+/g, '-') || 'section'
  );
}

function plainText(markdown: string): string {
  return markdown
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/<[^>]+>/g, ' ')
    .replace(/!\[[^\]]*\]\([^)]*\)/g, ' ')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}\u{FE0F}\u{200D}]/gu, ' ')
    .replace(/[#>*_`~|]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function makeDescription(markdown: string): string {
  const text = plainText(markdown);
  if (text.length <= 160) {
    return text;
  }
  const truncated = text.slice(0, 157);
  const lastSpace = truncated.lastIndexOf(' ');
  return `${truncated.slice(0, lastSpace > 0 ? lastSpace : 157).trimEnd()}…`;
}

function rewriteLinks(markdown: string): string {
  return markdown
    // The plugin mirrors the root screenshot into website/static, so serve the
    // built asset instead of GitHub.
    .replace(
      /\]\((?:fastlane\/metadata\/android\/en-US\/images\/)?screenshot\.webp\)/g,
      '](/screenshot.webp)',
    )
    .replace(
      /\]\(opentunnel-server\)/g,
      `](${REPO_URL}/tree/main/opentunnel-server)`,
    )
    .replace(
      /\]\(\.github\/workflows\/reproducible-build\.yml\)/g,
      `](${REPO_URL}/blob/main/.github/workflows/reproducible-build.yml)`,
    )
    .replace(
      /\]\(scripts\/generate-certificate\.sh\)/g,
      `](${REPO_URL}/blob/main/scripts/generate-certificate.sh)`,
    );
}

function extractDetails(markdown: string): {
  withoutDetails: string;
  details: Detail[];
} {
  const details: Detail[] = [];
  const withoutDetails = markdown.replace(
    /<details>\s*<summary>([\s\S]*?)<\/summary>([\s\S]*?)<\/details>/gi,
    (_match, summary: string, body: string) => {
      details.push({
        label: normalizeHeading(summary),
        body: body.trim(),
      });
      return '\n';
    },
  );
  return {withoutDetails, details};
}

function splitTopLevelSections(markdown: string): Section[] {
  const sections: Section[] = [];
  let current: Section = {label: null, lines: []};
  let fence: string | null = null;

  for (const line of markdown.split(/\r?\n/)) {
    const fenceMatch = line.match(/^\s*(`{3,}|~{3,})/);
    if (fenceMatch) {
      const marker = fenceMatch[1][0];
      if (fence === null) {
        fence = marker;
      } else if (fence === marker) {
        fence = null;
      }
      current.lines.push(line);
      continue;
    }

    const heading = fence === null ? line.match(/^##\s+(.+?)\s*$/) : null;

    if (heading) {
      sections.push(current);
      current = {
        label: normalizeHeading(heading[1]),
        lines: [],
      };
    } else {
      current.lines.push(line);
    }
  }

  sections.push(current);
  return sections;
}

function buildDoc({
  label,
  body,
  order,
  usedSlugs,
}: {
  label: string;
  body: string;
  order: number;
  usedSlugs: Set<string>;
}): Doc {
  const meta = SECTION_META[label];
  let slug = meta?.slug ?? slugify(label ?? 'overview');
  const baseSlug = slug;
  let suffix = 2;
  while (usedSlugs.has(slug)) {
    slug = `${baseSlug}-${suffix}`;
    suffix += 1;
  }
  usedSlugs.add(slug);

  const title = meta?.title ?? (label || 'Overview');
  const category = meta?.category ?? 'More';
  const content = rewriteLinks(body).trim();
  const description = meta?.description ?? makeDescription(content);

  const frontMatter = [
    '---',
    `title: ${JSON.stringify(title)}`,
    `sidebar_label: ${JSON.stringify(title)}`,
    `description: ${JSON.stringify(description)}`,
    `sidebar_position: ${order}`,
    '---',
    '',
  ].join('\n');

  return {
    slug,
    title,
    category,
    direct: meta?.direct ?? false,
    order,
    content: `${frontMatter}${content}\n`,
  };
}

function buildSidebar(docs: Doc[]): string {
  const groups = new Map<string, Doc[]>();
  for (const doc of docs) {
    if (!groups.has(doc.category)) {
      groups.set(doc.category, []);
    }
    groups.get(doc.category)!.push(doc);
  }

  const sidebarEntries: Array<string | SidebarCategory> = [];
  for (const category of CATEGORY_ORDER) {
    const categoryDocs = groups.get(category);
    if (!categoryDocs?.length) {
      continue;
    }
    const sortedDocs = [...categoryDocs].sort((a, b) => a.order - b.order);

    // A single-page group becomes a direct sidebar link rather than a
    // category heading with one child of the same name.
    if (sortedDocs.length === 1 && sortedDocs[0].direct) {
      sidebarEntries.push(sortedDocs[0].slug);
      continue;
    }

    sidebarEntries.push({
      type: 'category',
      label: category,
      // Always show every section. The sidebar is generated navigation, not
      // something visitors should have to expand first.
      collapsible: false,
      collapsed: false,
      items: sortedDocs.map((doc) => doc.slug),
    });
  }

  return [
    '// This file is generated by plugins/readme-docs. Do not edit by hand.',
    "'use strict';",
    '',
    '/** @type {import(\'@docusaurus/plugin-content-docs\').SidebarsConfig} */',
    'const sidebars = {',
    '  docs: [',
    ...sidebarEntries.map((entry) => `    ${JSON.stringify(entry)},`),
    '  ],',
    '};',
    '',
    'module.exports = sidebars;',
    '',
  ].join('\n');
}

function resolveReadmePath(siteDir: string, explicitPath?: string): string {
  const candidates = [
    explicitPath,
    process.env.README_PATH,
    // In the Docker setup the website is /workspace/website and the README is
    // /workspace/README.md; locally this is website/../README.md.
    path.resolve(siteDir, '..', 'README.md'),
    '/README.md',
    path.resolve(siteDir, 'README.md'),
  ].filter((candidate): candidate is string => Boolean(candidate));

  for (const candidate of candidates) {
    const resolved = path.resolve(candidate);
    if (fs.existsSync(resolved)) {
      return resolved;
    }
  }

  throw new Error(
    `[readme-docs] Could not find README.md. Tried:\n${candidates
      .map((candidate) => `  - ${path.resolve(candidate)}`)
      .join('\n')}`,
  );
}

/**
 * The README's screenshot lives at the repository root, but Docusaurus only
 * serves files from its static directories. Mirror it into website/static
 * before the dev server or build reads those directories.
 */
function mirrorRootScreenshot(siteDir: string): void {
  const source = path.resolve(siteDir, '..', ROOT_SCREENSHOT);
  const destination = path.resolve(siteDir, 'static', ROOT_SCREENSHOT);

  if (!fs.existsSync(source)) {
    throw new Error(
      `[readme-docs] Could not find ${ROOT_SCREENSHOT} at ${source}`,
    );
  }

  fs.mkdirSync(path.dirname(destination), {recursive: true});
  fs.copyFileSync(source, destination);
  console.log(`[readme-docs] Copied ${source} to ${destination}`);
}

export function generateReadmeDocs({
  readmePath,
  outputDir,
  sidebarFile,
}: {
  readmePath: string;
  outputDir: string;
  sidebarFile: string;
}): {docs: Doc[]; readmePath: string; outputDir: string} {
  const readme = fs.readFileSync(readmePath, 'utf8');
  const {withoutDetails, details} = extractDetails(readme);

  // The README's H1 becomes the site title, not a docs heading.
  const body = withoutDetails.replace(/^#\s+.*(?:\r?\n)+/, '');

  const sections = splitTopLevelSections(body);
  const blocks: Block[] = [];

  for (const section of sections) {
    const content = section.lines.join('\n').trim();
    if (!content) {
      continue;
    }
    blocks.push({
      kind: 'section',
      label: section.label ?? 'Overview',
      body: content,
    });
  }

  for (const detail of details) {
    blocks.push({
      kind: 'detail',
      label: detail.label,
      body: detail.body,
    });
  }

  const usedSlugs = new Set<string>();
  const docs: Doc[] = [];
  const groupedSectionLabels = new Set(
    PAGE_GROUPS.flatMap((group) => group.members),
  );

  // Build the multi-section pages first, in sidebar order.
  for (const group of PAGE_GROUPS) {
    const members = group.members
      .map((label) =>
        blocks.find(
          (block) => block.kind === 'section' && block.label === label,
        ),
      )
      .filter((block): block is Block => Boolean(block));

    if (!members.length) {
      continue;
    }

    const [first, ...rest] = members;
    const parts = [first.body];

    for (const member of rest) {
      const memberTitle = SECTION_META[member.label]?.title ?? member.label;
      parts.push(`## ${memberTitle}\n\n${member.body}`);
    }

    docs.push(
      buildDoc({
        label: group.label,
        body: parts.join('\n\n'),
        order: docs.length,
        usedSlugs,
      }),
    );
  }

  for (const block of blocks) {
    if (block.kind === 'section' && groupedSectionLabels.has(block.label)) {
      continue;
    }
    docs.push(
      buildDoc({
        label: block.label,
        body: block.body,
        order: docs.length,
        usedSlugs,
      }),
    );
  }

  fs.rmSync(outputDir, {recursive: true, force: true});
  fs.mkdirSync(outputDir, {recursive: true});

  for (const doc of docs) {
    fs.writeFileSync(path.join(outputDir, `${doc.slug}.md`), doc.content);
  }

  fs.writeFileSync(path.join(outputDir, sidebarFile), buildSidebar(docs));

  return {docs, readmePath, outputDir};
}

export default function readmeDocsPlugin(
  context: LoadContext,
  rawOptions: unknown,
): Plugin {
  const options = (rawOptions ?? {}) as ReadmeDocsPluginOptions;
  const outputDir = path.resolve(
    context.siteDir,
    options.outputDir ?? DEFAULT_OUTPUT_DIR,
  );
  const readmePath = resolveReadmePath(context.siteDir, options.readmePath);
  const sidebarFile = options.sidebarFile ?? DEFAULT_SIDEBAR_FILE;

  mirrorRootScreenshot(context.siteDir);

  const {docs} = generateReadmeDocs({
    readmePath,
    outputDir,
    sidebarFile,
  });

  console.log(
    `[readme-docs] Generated ${docs.length} documentation pages from ${readmePath}`,
  );

  return {
    name: 'readme-docs',
    getPathsToWatch() {
      return [readmePath];
    },
  };
}

export {resolveReadmePath};
