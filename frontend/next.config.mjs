/** @type {import('next').NextConfig} */
const nextConfig = {
  distDir: process.env.LANDROP_NEXT_OUTPUT || '.next',
  reactStrictMode: true,
  poweredByHeader: false,
  ...(process.env.LANDROP_BUILD_WORKERS === 'threads' ? {
    experimental: { workerThreads: true, webpackBuildWorker: false },
  } : {}),
};

export default nextConfig;
