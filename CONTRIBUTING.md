# Contributing

## Branching

Each Jira ticket gets its own branch off `main`:

```
feature/FORMS-<n>-short-description
```

Example: `feature/FORMS-8-git-branching-strategy`.

Work happens on the feature branch and is pushed to `origin` as it progresses. Direct pushes to feature branches are fine — no review requirement for this solo project.

## Merging

Merge feature branches into `main` with `--no-ff`, so every ticket's work stays visible as its own merge commit in `main`'s history, even when the branch was a fast-forward:

```bash
git checkout main
git merge --no-ff feature/FORMS-<n>-short-description
git push origin main
```

Push both the feature branch and `main` after merging.

## Commit messages

Use Jira smart-commit syntax so commits are traceable to tickets:

```
FORMS-<n> #comment <what changed>
```

Ticket status transitions (To Do → In Progress → Done) are done explicitly via Jira, not via smart-commit keywords like `#done`.

## `main` protection

Force-pushes to `main` are disabled. History on `main` is append-only via merge commits — if a mistake lands on `main`, fix it forward with a new commit rather than rewriting history.
