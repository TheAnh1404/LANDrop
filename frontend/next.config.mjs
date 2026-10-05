/** @type {import('next').NextConfig} */
const nextConfig = {
  distDir: process.env.LANDROP_NEXT_OUTPUT || '.next',
  reactStrictMode: true,
  poweredByHeader: false,
};

export default nextConfig;
