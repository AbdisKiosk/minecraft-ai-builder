import type { Metadata, Viewport } from 'next';
import type { ReactNode } from 'react';
import './globals.css';

export const metadata: Metadata = {
  title: 'AIBuilder: let Claude build on your Minecraft server',
  icons: {
    icon: "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16'%3E%3Cpath d='M8 1 15 4.5v7L8 15 1 11.5v-7z' fill='%2350b37a'/%3E%3Cpath d='M8 8v7L1 11.5v-7z' fill='%23367a54'/%3E%3C/svg%3E",
  },
};

export const viewport: Viewport = { width: 'device-width', initialScale: 1 };

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
