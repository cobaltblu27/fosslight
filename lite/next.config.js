/** @type {import('next').NextConfig} */
const isCI = process.env.BUILD_ENV === 'ci';

const nextConfig = isCI ? {
  output: 'export',
  images: {
    unoptimized: true,
  },
} : {
};

module.exports = nextConfig;
