# GitHub Pages setup

To have the architecture report published on each push to `main`, configure the repository once as follows.

## 1. Enable GitHub Pages from Actions

1. On GitHub, open your repository.
2. Go to **Settings** (tab in the repo).
3. In the left sidebar, under **Code and automation**, click **Pages**.
4. Under **Build and deployment**:
   - **Source**: choose **GitHub Actions** (not “Deploy from a branch”).

That’s all. No need to create a branch or a `gh-pages` branch.

## 2. What happens automatically

- The **`github_pages`** environment is created by GitHub when you select “GitHub Actions” as the source. You don’t need to create it.
- On every **push** to `main`, the workflow:
  1. Builds all modules (sample, then archimo) and runs tests.
  2. Generates the report from the sample app into `archimo/target/modulith-docs/site`.
  3. Uploads that folder as the Pages artifact.
  4. Runs the **deploy** job, which publishes the site.

- **Pull requests** only run the build job (no deploy), so the site is not updated on PRs.

## 3. Viewing the site

After the first successful deploy, the site is available at:

**`https://<owner>.github.io/<repo>/`**

Example: [https://ludoo0d0a.github.io/archimo/](https://ludoo0d0a.github.io/archimo/)

You can also open it from the repo: **Settings → Pages** shows the link.

## 4. Optional: deployment protection

To allow only the default branch to deploy:

1. **Settings → Environments** (left sidebar).
2. Click **github_pages**.
3. Under **Deployment protection rules**, add **Required reviewers** or **Wait timer** if you want (optional).

## 5. If the deploy job fails

- Check **Actions** → select the workflow run → open the **deploy** job.
- Ensure **Source** is set to **GitHub Actions** (step 1 above).
- For organization repos, ensure GitHub Pages is allowed in **Organization Settings → Pages**.
