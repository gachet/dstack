export default {
    DOCS_URL: 'http://docs.dstack.ai',

    LOGIN_URL: '/users/login',
    VERIFY_EMAIL_URL: '/users/verify',
    SUPPORT_URL: '/support/submit',
    SIGN_UP_URL: '/users/register',
    RESET_PASSWORD_URL: '/users/reset',
    UPDATE_PASSWORD_URL: '/users/update/password',

    USER_DATA_URL: '/users/remember',
    UPDATE_TOKEN_URL: '/users/update/token',
    UPDATE_SETTINGS_URL: '/users/update/settings',
    CHECK_USER: userName => `/users/exists/${userName}`,

    STACKS_LIST: userName => `/stacks/${userName}`,
    DELETE_STACK: () => '/stacks/delete',
    STACK_DETAILS: (userName, stack) => `/stacks/${userName}/${stack}`,
    STACK_FRAME: (userName, stack, frameId) => `/frames/${userName}/${stack}/${frameId}`,
    STACK_ATTACHMENT: (stack, frameId, id) => `/attachs/${stack}/${frameId}/${id}`,
    STACK_UPDATE: '/stacks/update',
    STACK_PUSH: '/stacks/push',

    DASHBOARD_LIST: userName => `/dashboards/${userName}`,
    DASHBOARD_DETAILS: (userName, id) => `/dashboards/${userName}/${id}`,
    DASHBOARD_CREATE: '/dashboards/create',
    DASHBOARD_UPDATE: '/dashboards/update',
    DASHBOARD_DELETE: '/dashboards/delete',
    DASHBOARD_CARDS_INSERT: '/dashboards/cards/insert',
    DASHBOARD_CARDS_UPDATE: '/dashboards/cards/update',
    DASHBOARD_CARDS_DELETE: '/dashboards/cards/delete',

    JOB_LIST: userName => `/jobs/${userName}`,
    JOB_DETAILS: (userName, id) => `/jobs/${userName}/${id}`,
    JOB_CREATE: '/jobs/create',
    JOB_UPDATE: '/jobs/update',
    JOB_DELETE: '/jobs/delete',
    JOB_RUN: '/jobs/run',
    JOB_STOP: '/jobs/stop',

    DISCORD_URL: 'https://discord.gg/8xfhEYa',
    TWITTER_URL: 'https://twitter.com/dstackai',
    GITHUB_URL: 'https://github.com/dstackai',
    BLOG_URL: 'https://blog.dstack.ai',

    TOKEN_STORAGE_KEY: 'token',
};

export const reportPlotPythonCode = `import matplotlib.pyplot as plt
import dstack as ds

fig = plt.figure()
plt.plot([1, 2, 3, 4], [1, 4, 9, 16])

ds.push_frame("simple", fig, "My first plot")`;

export const installRPackageCode = 'install.packages("dstack")';

export const reportPlotRCode = `library(ggplot2)
library(dstack)

df <- data.frame(x = c(1, 2, 3, 4), y = c(1, 4, 9, 16))
image <- ggplot(data = df, aes(x = x, y = y)) + geom_line()

push_frame("simple", image, "My first plot")`;
