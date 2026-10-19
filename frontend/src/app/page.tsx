import styles from "./page.module.css";

export default function Home() {
  return (
    <main className={styles.page}>
      <header>
        <p>ASEP / M01 FOUNDATION</p>

        <h1>Agentic Software Engineering Platform</h1>

        <p>
          A self-hosted platform for reliable AI-assisted software engineering
          workflows.
        </p>
      </header>

      <section aria-labelledby="milestone-heading">
        <h2 id="milestone-heading">M01 — AI Merge Request Reviewer</h2>

        <p>
          Application foundation is under development. GitLab integrations and
          AI review workflows are not available yet.
        </p>
      </section>

      <section aria-labelledby="technology-heading">
        <h2 id="technology-heading">Technology Foundation</h2>

        <ul>
          <li>Frontend: Next.js + TypeScript</li>
          <li>Backend: Java + Spring Boot + Spring Modulith</li>
          <li>Architecture: Modular Monolith</li>
        </ul>
      </section>
    </main>
  );
}
