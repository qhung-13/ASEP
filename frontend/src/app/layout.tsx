import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "ASEP | Agentic Software Engineering Platform",
  description:
    "A self-hosted platform for reliable AI-assisted software engineering workflows.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
