import type {ComponentProps, ReactNode} from 'react';

export type IconName =
  | 'battery'
  | 'broadcast'
  | 'camera'
  | 'check'
  | 'globe'
  | 'motion'
  | 'shield'
  | 'sliders';

type IconProps = ComponentProps<'svg'> & {
  name: IconName;
  children?: never;
};

const paths: Record<IconName, ReactNode> = {
  battery: (
    <>
      <rect x="3" y="7" width="16" height="10" rx="2.5" />
      <path d="M21 10v4" />
      <path d="M7 10v4M10.5 10v4M14 10v4" />
    </>
  ),
  broadcast: (
    <>
      <circle cx="12" cy="12" r="2.2" fill="currentColor" stroke="none" />
      <path d="M7.8 7.8a6 6 0 0 0 0 8.4M16.2 7.8a6 6 0 0 1 0 8.4" />
      <path d="M4.9 4.9a10 10 0 0 0 0 14.2M19.1 4.9a10 10 0 0 1 0 14.2" />
    </>
  ),
  camera: (
    <>
      <rect x="3" y="6.5" width="13" height="11" rx="2.5" />
      <path d="m16 10 5-2.6v9.2L16 14" />
    </>
  ),
  check: <path d="m5 12.5 4.2 4.2L19 7" />,
  globe: (
    <>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M3.8 9.5h16.4M3.8 14.5h16.4" />
      <path d="M12 3.5c2.2 2.3 3.3 5.2 3.3 8.5S14.2 18.2 12 20.5C9.8 18.2 8.7 15.3 8.7 12S9.8 5.8 12 3.5Z" />
    </>
  ),
  motion: (
    <>
      <path d="M3 12h3.2l2.1-5.2 3.4 10.4 2.2-5.2H21" />
      <path d="M4 5.5h3M17 18.5h3" />
    </>
  ),
  shield: (
    <>
      <path d="M12 3.2 5 6v5.3c0 4.2 2.8 7.3 7 9.5 4.2-2.2 7-5.3 7-9.5V6l-7-2.8Z" />
      <path d="m9 11.8 2 2 4-4.2" />
    </>
  ),
  sliders: (
    <>
      <path d="M4 7h6M14 7h6M4 17h10M18 17h2" />
      <circle cx="12" cy="7" r="2" />
      <circle cx="16" cy="17" r="2" />
    </>
  ),
};

export function Icon({name, ...props}: IconProps): ReactNode {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      {...props}>
      {paths[name]}
    </svg>
  );
}
