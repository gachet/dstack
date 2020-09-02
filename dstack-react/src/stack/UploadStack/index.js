// @flow

import React, {useState} from 'react';
import {useTranslation} from 'react-i18next';
import CodeViewer from '../../CodeViewer';
import Tabs from '../../Tabs';
import Upload from './Upload';
import css from './styles.module.css';
import config from '../../config';
import {reportPlotPythonCode, installRPackageCode, reportPlotRCode} from '../../config';

type Props = {
    user?: string,
    token?: string,
    refresh?: Function,
    apiUrl: string,
}

const UploadStack = ({user, token, refresh, apiUrl}: Props) => {
    const {t} = useTranslation();
    const [activeCodeTab, setActiveCodeTab] = useState(1);
    const [activePlatformTab, setActivePlatformTab] = useState(1);

    return (
        <div className={css.howto}>
            <Tabs
                className={css.tabs}
                value={activeCodeTab}
                onChange={setActiveCodeTab}

                tabs={[
                    {
                        label: t('python'),
                        value: 1,
                    },

                    {
                        label: t('r'),
                        value: 2,
                    },

                    {
                        label: t('upload'),
                        value: 3,
                    },
                ]}
            />

            {activeCodeTab === 1 && <div>
                <div className={css.description}>{t('installPipOrCondaPackage')}</div>

                <Tabs
                    className={css.tabs}
                    value={activePlatformTab}
                    onChange={setActivePlatformTab}

                    tabs={[
                        {
                            label: t('pip'),
                            value: 1,
                        },

                        {
                            label: t('conda'),
                            value: 2,
                        },
                    ]}
                />

                {activePlatformTab === 1 && <CodeViewer
                    className={css.code}
                    language="bash"
                >
                    pip install dstack
                </CodeViewer>}

                {activePlatformTab === 2 && <CodeViewer
                    className={css.code}
                    language="bash"
                >
                    conda install dstack -c dstack.ai
                </CodeViewer>}

                <div className={css.description}>{t('configureDStack')}</div>

                <CodeViewer
                    className={css.code}
                    language="bash"
                >
                    {config.CONFIGURE_PYTHON_COMMAND(token, user)}
                </CodeViewer>

                <div className={css.description}>{t('reportPlotIntro')}</div>

                <CodeViewer
                    className={css.code}
                    language="python"
                >
                    {reportPlotPythonCode}
                </CodeViewer>
            </div>}

            {activeCodeTab === 2 && <div>
                <div className={css.description}>{t('installRPackage')}</div>

                <CodeViewer
                    className={css.code}
                    language="r"
                >
                    {installRPackageCode}
                </CodeViewer>

                <div className={css.description}>{t('configureDStack')}</div>

                <CodeViewer
                    className={css.code}
                    language="r"
                >
                    {config.CONFIGURE_R_COMMAND(token, user)}
                </CodeViewer>

                <div className={css.description}>{t('reportPlotIntro')}</div>

                <CodeViewer
                    className={css.code}
                    language="r"
                >
                    {reportPlotRCode}
                </CodeViewer>
            </div>}

            {activeCodeTab === 3 && <Upload
                user={user}
                refresh={refresh}
                apiUrl={apiUrl}
            />}

            <div
                className={css.footer}
                dangerouslySetInnerHTML={{__html: t('notClearCheckTheDocks', {href: config.DOCS_URL})}}
            />
        </div>
    );
};

export default UploadStack;