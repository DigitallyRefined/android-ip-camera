import clsx from 'clsx';
import Link from '@docusaurus/Link';
import {Icon} from '@site/src/components/Icons';
import type {ReactNode} from 'react';

const sponsorUrl = 'https://github.com/sponsors/DigitallyRefined';

type Props = {
  mobile?: boolean;
};

export default function SponsorNavbarItem({mobile = false}: Props): ReactNode {
  const link = (
    <Link
      className={clsx(
        mobile ? 'menu__link' : 'navbar__item navbar__link',
        'navbar-sponsor',
      )}
      href={sponsorUrl}
      target="_blank"
      rel="noopener noreferrer"
      title="Sponsor this project on GitHub">
      <Icon name="heart" className="navbar-sponsor__icon" />
      <span>Sponsor</span>
    </Link>
  );

  if (mobile) {
    return <li className="menu__list-item">{link}</li>;
  }

  return link;
}
